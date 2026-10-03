package zw.co.innbucks.loans.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the single-flight token cache's concurrency rules. Pure JUnit: the login is a lambda that counts
 * its calls and can be held on a latch, which stands in for a slow or hung upstream.
 */
class SingleFlightTokenCacheTest {

    private static final Duration MARGIN = Duration.ofSeconds(60);
    private static final Duration LIFETIME = Duration.ofSeconds(300);
    private static final int CALLERS = 16;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-03T08:00:00Z"));
    private final ExecutorService pool = Executors.newCachedThreadPool();
    /** Released in tearDown so no test leaves a login thread parked. */
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        pool.shutdownNow();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("a token valid beyond the margin is served without a login")
    void freshToken_isServedWithoutALogin() {
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache cache = cache(() -> token("t" + logins.incrementAndGet()), Duration.ofSeconds(5));

        assertThat(cache.get()).isEqualTo("t1");
        clock.advance(LIFETIME.minus(MARGIN).minusSeconds(1));
        assertThat(cache.get()).isEqualTo("t1");
        assertThat(logins).hasValue(1);
    }

    @Test
    @DisplayName("while a refresh is blocked upstream, callers holding a still-valid token never wait")
    void validTokenCallers_neverWait_duringABlockedRefresh() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        CountDownLatch secondLoginStarted = new CountDownLatch(1);
        SingleFlightTokenCache cache = cache(() -> {
            int n = logins.incrementAndGet();
            if (n > 1) {
                secondLoginStarted.countDown();
                awaitRelease();
            }
            return token("t" + n);
        }, Duration.ofSeconds(30));

        assertThat(cache.get()).isEqualTo("t1");
        clock.advance(LIFETIME.minus(MARGIN).plusSeconds(1)); // inside the margin, not yet expired

        CompletableFuture<String> refresher = CompletableFuture.supplyAsync(cache::get, pool);
        assertThat(secondLoginStarted.await(5, TimeUnit.SECONDS)).isTrue();

        List<CompletableFuture<String>> others = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            others.add(CompletableFuture.supplyAsync(cache::get, pool));
        }
        for (CompletableFuture<String> other : others) {
            // The login is still parked: an answer here proves nobody waited for it.
            assertThat(other.get(2, TimeUnit.SECONDS)).isEqualTo("t1");
        }
        assertThat(refresher).isNotDone();

        release.countDown();
        assertThat(refresher.get(5, TimeUnit.SECONDS)).isEqualTo("t2");
        assertThat(cache.get()).isEqualTo("t2");
        assertThat(logins).hasValue(2);
    }

    @Test
    @DisplayName("N cold callers cause exactly one login")
    void coldCallers_causeOneLogin() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        CountDownLatch loginStarted = new CountDownLatch(1);
        SingleFlightTokenCache cache = cache(() -> {
            logins.incrementAndGet();
            loginStarted.countDown();
            awaitRelease();
            return token("t1");
        }, Duration.ofSeconds(30));

        List<Thread> threads = new ArrayList<>();
        List<CompletableFuture<String>> results = new ArrayList<>();
        CompletableFuture<String> first = new CompletableFuture<>();
        threads.add(start(() -> first.complete(cache.get())));
        results.add(first);
        assertThat(loginStarted.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 1; i < CALLERS; i++) {
            CompletableFuture<String> r = new CompletableFuture<>();
            threads.add(start(() -> r.complete(cache.get())));
            results.add(r);
        }
        awaitParked(threads.subList(1, threads.size()));

        release.countDown();
        for (CompletableFuture<String> r : results) {
            assertThat(r.get(5, TimeUnit.SECONDS)).isEqualTo("t1");
        }
        assertThat(logins).hasValue(1);
    }

    @Test
    @DisplayName("N concurrent refreshes after a 401 on the same token cause exactly one login")
    void concurrentRejections_causeOneLogin() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        CountDownLatch secondLoginStarted = new CountDownLatch(1);
        SingleFlightTokenCache cache = cache(() -> {
            int n = logins.incrementAndGet();
            if (n > 1) {
                secondLoginStarted.countDown();
                awaitRelease();
            }
            return token("t" + n);
        }, Duration.ofSeconds(30));
        String refused = cache.get();

        List<Thread> threads = new ArrayList<>();
        List<CompletableFuture<String>> results = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            CompletableFuture<String> r = new CompletableFuture<>();
            threads.add(start(() -> r.complete(cache.refreshAfterRejection(refused))));
            results.add(r);
        }
        assertThat(secondLoginStarted.await(5, TimeUnit.SECONDS)).isTrue();
        awaitParked(threads); // one parked in the login, the rest on its future

        release.countDown();
        for (CompletableFuture<String> r : results) {
            assertThat(r.get(5, TimeUnit.SECONDS)).isEqualTo("t2");
        }
        // A 401 that arrives late, for the token already replaced, reuses the new one.
        assertThat(cache.refreshAfterRejection(refused)).isEqualTo("t2");
        assertThat(logins).hasValue(2);
    }

    @Test
    @DisplayName("a refused token is never handed out again, even before it expires")
    void rejectedToken_isDropped() {
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache cache = cache(() -> {
            int n = logins.incrementAndGet();
            if (n == 2) {
                throw new IllegalStateException("upstream down");
            }
            return token("t" + n);
        }, Duration.ofSeconds(5));
        String refused = cache.get();

        assertThatThrownBy(() -> cache.refreshAfterRejection(refused)).hasMessage("upstream down");
        assertThat(cache.get()).isEqualTo("t3");
    }

    @Test
    @DisplayName("a hung login bounds how long a caller with no token waits, with the client's own error")
    void hungLogin_isBounded() throws Exception {
        CountDownLatch loginStarted = new CountDownLatch(1);
        SingleFlightTokenCache cache = cache(() -> {
            loginStarted.countDown();
            awaitRelease();
            return token("late");
        }, Duration.ofMillis(300));
        pool.submit(cache::get);
        assertThat(loginStarted.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThatThrownBy(cache::get)
                .isInstanceOf(UpstreamUnavailable.class)
                .hasMessage("timed out waiting for login");
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(waitedMs).isBetween(250L, 3000L);
    }

    @Test
    @DisplayName("a failed login caches nothing and the next caller logs in again")
    void failedLogin_isRetriedByTheNextCaller() {
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache cache = cache(() -> {
            if (logins.incrementAndGet() == 1) {
                throw new UpstreamUnavailable("login refused");
            }
            return token("t2");
        }, Duration.ofSeconds(5));

        assertThatThrownBy(cache::get).isInstanceOf(UpstreamUnavailable.class).hasMessage("login refused");
        assertThat(cache.get()).isEqualTo("t2");
        assertThat(logins).hasValue(2);
    }

    @Test
    @DisplayName("callers waiting on a login that fails get its error, not a hang")
    void joinersOfAFailedLogin_getItsError() throws Exception {
        CountDownLatch loginStarted = new CountDownLatch(1);
        SingleFlightTokenCache cache = cache(() -> {
            loginStarted.countDown();
            awaitRelease();
            throw new UpstreamUnavailable("login refused");
        }, Duration.ofSeconds(30));
        CompletableFuture<Throwable> owner = new CompletableFuture<>();
        start(() -> owner.complete(failureOf(cache::get)));
        assertThat(loginStarted.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> joiner = new CompletableFuture<>();
        Thread joinerThread = start(() -> joiner.complete(failureOf(cache::get)));
        awaitParked(List.of(joinerThread));

        release.countDown();
        assertThat(owner.get(5, TimeUnit.SECONDS)).isInstanceOf(UpstreamUnavailable.class);
        assertThat(joiner.get(5, TimeUnit.SECONDS)).isInstanceOf(UpstreamUnavailable.class)
                .hasMessage("login refused");
    }

    @Test
    @DisplayName("a failed refresh keeps serving the token that has not expired yet")
    void failedRefresh_keepsTheUnexpiredToken() {
        AtomicInteger logins = new AtomicInteger();
        SingleFlightTokenCache cache = cache(() -> {
            if (logins.incrementAndGet() == 2) {
                throw new UpstreamUnavailable("login refused");
            }
            return token("t" + logins.get());
        }, Duration.ofSeconds(5));
        assertThat(cache.get()).isEqualTo("t1");
        clock.advance(LIFETIME.minus(MARGIN).plusSeconds(1));

        assertThat(cache.get()).isEqualTo("t1");
        assertThat(cache.get()).isEqualTo("t3");
    }

    @Test
    @DisplayName("the token never appears in toString")
    void tokenToString_hidesTheValue() {
        assertThat(new SingleFlightTokenCache.Token("secret-token", clock.instant()).toString())
                .doesNotContain("secret-token");
    }

    @Test
    @DisplayName("the wait bound is connect + read timeout + the margin")
    void waitBound() {
        assertThat(SingleFlightTokenCache.waitBound(3000, 20000)).isEqualTo(Duration.ofSeconds(25));
    }

    // ---------------------------------------------------------------------------------------------

    private SingleFlightTokenCache cache(Supplier<SingleFlightTokenCache.Token> login, Duration maxWait) {
        return new SingleFlightTokenCache(login::get, MARGIN, maxWait,
                () -> new UpstreamUnavailable("timed out waiting for login"), clock);
    }

    private SingleFlightTokenCache.Token token(String value) {
        return new SingleFlightTokenCache.Token(value, clock.instant().plus(LIFETIME));
    }

    private void awaitRelease() {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailable("interrupted");
        }
    }

    private Thread start(Runnable body) {
        Thread t = new Thread(body);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Throwable failureOf(Runnable call) {
        try {
            call.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** Waits until every thread is parked: in the held login, or waiting on its future. */
    private static void awaitParked(List<Thread> threads) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (threads.stream().allMatch(SingleFlightTokenCacheTest::parked)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("callers never reached the wait");
    }

    private static boolean parked(Thread t) {
        Thread.State s = t.getState();
        return s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING;
    }

    private static final class UpstreamUnavailable extends RuntimeException {
        UpstreamUnavailable(String message) {
            super(message);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
