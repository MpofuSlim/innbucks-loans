package zw.co.innbucks.loans.core.voucher;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.task.TaskDecorator;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.config.RejectedTasks;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The GetMore dispatcher as Spring builds it (D18): a borrower's read never waits on GetMore. Its executor's queue is
 * bounded, work that does not fit is counted and never thrown at the reader, every task runs under the context's
 * {@link TaskDecorator} (the trace, in the application), and work already queued under a key is not queued again.
 */
class GetMoreDispatcherExecutorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T08:40:00Z");
    private static final ThreadLocal<String> MARK = new ThreadLocal<>();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> runs = new CopyOnWriteArrayList<>();
    private GetMoreVoucherSync sync;
    private StaffLoanVoucherIssuer issuer;
    private VoucherRepository vouchers;
    private GetMoreProperties properties;
    private GetMoreDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        sync = mock(GetMoreVoucherSync.class);
        issuer = mock(StaffLoanVoucherIssuer.class);
        vouchers = mock(VoucherRepository.class);
        properties = new GetMoreProperties();
        properties.setMode(GetMoreMode.SIMULATED);
    }

    @AfterEach
    void stop() {
        release.countDown();
        if (dispatcher != null) {
            dispatcher.stop();
        }
    }

    private GetMoreDispatcher dispatcher(TaskDecorator decorator) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", registry);
        if (decorator != null) {
            beans.registerSingleton("traceContextTaskDecorator", decorator);
        }
        return new GetMoreDispatcher(sync, issuer, vouchers, properties,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)), beans.getBeanProvider(TaskDecorator.class),
                beans.getBeanProvider(MeterRegistry.class));
    }

    private double rejected() {
        return registry.get(RejectedTasks.METRIC).tag("executor", "getmore").counter().count();
    }

    private Runnable recording(String name, boolean hold) {
        return () -> {
            if (hold) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            runs.add(name + " " + MARK.get());
        };
    }

    @Test
    @DisplayName("a full queue: the work is counted and dropped, the caller gets no exception, the rest still runs")
    void fullQueue() {
        dispatcher = dispatcher(null);
        assertThat(rejected()).as("registered at 0").isZero();

        assertThat(dispatcher.submit("hold", recording("hold", true))).isTrue();      // on the thread, held
        for (int i = 1; i <= GetMoreDispatcher.QUEUE_CAPACITY; i++) {
            assertThat(dispatcher.submit("sync:" + i, recording("sync:" + i, false))).isTrue();  // fills the queue
        }
        assertThatCode(() -> assertThat(dispatcher.submit("one-too-many", recording("x", false))).isFalse())
                .doesNotThrowAnyException();
        assertThatCode(() -> dispatcher.sync(-1L, GetMoreSyncTrigger.BORROWER_READ)).doesNotThrowAnyException();

        assertThat(rejected()).isEqualTo(2);
        assertThat(dispatcher.isQueued("one-too-many")).as("a refused key can be asked again").isFalse();
        release.countDown();
        await().atMost(10, TimeUnit.SECONDS).until(() -> runs.size() == GetMoreDispatcher.QUEUE_CAPACITY + 1);
        assertThat(runs).noneMatch(run -> run.startsWith("x "));
    }

    @Test
    @DisplayName("each task runs under the context's task decorator (the trace, in the application)")
    void decorated() {
        TaskDecorator decorator = task -> () -> {
            MARK.set("decorated");
            try {
                task.run();
            } finally {
                MARK.remove();
            }
        };
        dispatcher = dispatcher(decorator);

        dispatcher.submit("sync:9", recording("sync:9", false));

        await().atMost(5, TimeUnit.SECONDS).until(() -> !runs.isEmpty());
        assertThat(runs).containsExactly("sync:9 decorated");
    }

    @Test
    @DisplayName("work already queued or running under a key is not queued again; once done, it can be")
    void keysAreDeduplicated() {
        dispatcher = dispatcher(null);

        assertThat(dispatcher.submit("refresh:9", recording("first", true))).isTrue();
        assertThat(dispatcher.submit("refresh:9", recording("second", false))).as("running").isFalse();
        assertThat(dispatcher.submit("refresh:10", recording("other", false))).isTrue();
        assertThat(dispatcher.submit("refresh:10", recording("other again", false))).as("queued").isFalse();
        release.countDown();
        await().atMost(5, TimeUnit.SECONDS).until(() -> runs.size() == 2);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !dispatcher.isQueued("refresh:9"));

        assertThat(dispatcher.submit("refresh:9", recording("third", false))).isTrue();
        await().atMost(5, TimeUnit.SECONDS).until(() -> runs.size() == 3);
        assertThat(runs).containsExactly("first null", "other null", "third null");
    }

    @Test
    @DisplayName("a failing task is logged, never thrown, and frees its key")
    void failureFreesTheKey() {
        dispatcher = new GetMoreDispatcher(sync, issuer, vouchers, properties,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)), Runnable::run);

        assertThat(dispatcher.submit("sync:9", () -> {
            throw new IllegalStateException("GetMore down");
        })).isTrue();
        assertThat(dispatcher.isQueued("sync:9")).isFalse();
    }

    @Test
    @DisplayName("a refresh syncs the borrower's vouchers due one, in the background; OFF queues nothing")
    void refresh() {
        dispatcher = new GetMoreDispatcher(sync, issuer, vouchers, properties,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)), Runnable::run);
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(vouchers.findGetMoreIdsToRefresh(eq(9L), eq(GetMoreMode.SIMULATED), any(), any(), anyInt()))
                .thenReturn(List.of(12L, 9L));

        dispatcher.refresh(9L);

        verify(issuer).refreshFor(9L);
        verify(vouchers).findGetMoreIdsToRefresh(9L, GetMoreMode.SIMULATED, now.minus(Duration.ofDays(30)),
                now.minus(Duration.ofMinutes(1)), GetMoreDispatcher.VOUCHERS_PER_REFRESH);
        verify(sync).sync(12L, GetMoreSyncTrigger.BORROWER_READ);
        verify(sync).sync(9L, GetMoreSyncTrigger.BORROWER_READ);

        properties.setMode(GetMoreMode.OFF);
        GetMoreVoucherSync offSync = mock(GetMoreVoucherSync.class);
        VoucherRepository offVouchers = mock(VoucherRepository.class);
        StaffLoanVoucherIssuer offIssuer = mock(StaffLoanVoucherIssuer.class);
        new GetMoreDispatcher(offSync, offIssuer, offVouchers, properties,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)), Runnable::run).refresh(9L);
        verifyNoInteractions(offSync, offIssuer, offVouchers);
    }

    @Test
    @DisplayName("a cancelled loan's GetMore coupons are withdrawn in the background, once per issuance at a time")
    void cleanup() {
        dispatcher = new GetMoreDispatcher(sync, issuer, vouchers, properties,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)), Runnable::run);

        dispatcher.cleanup(101L);

        verify(issuer).cleanup(101L);
        assertThat(dispatcher.isQueued("cleanup:101")).isFalse();
    }

    @Test
    @DisplayName("issuances that cannot be moved on do not stop the vouchers' refresh")
    void refreshCarriesOnPastTheIssuances() {
        dispatcher = new GetMoreDispatcher(sync, issuer, vouchers, properties,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)), Runnable::run);
        org.mockito.Mockito.doThrow(new IllegalStateException("database went away")).when(issuer).refreshFor(9L);
        when(vouchers.findGetMoreIdsToRefresh(anyLong(), any(), any(), any(), anyInt())).thenReturn(List.of(9L));

        assertThatCode(() -> dispatcher.refresh(9L)).doesNotThrowAnyException();

        verify(sync).sync(9L, GetMoreSyncTrigger.BORROWER_READ);
    }

    @Test
    @DisplayName("one voucher's failing sync does not stop the refresh of the others")
    void refreshCarriesOn() {
        dispatcher = new GetMoreDispatcher(sync, issuer, vouchers, properties,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)), Runnable::run);
        when(vouchers.findGetMoreIdsToRefresh(anyLong(), any(), any(), any(), anyInt())).thenReturn(List.of(12L, 9L));
        when(sync.sync(12L, GetMoreSyncTrigger.BORROWER_READ)).thenThrow(new IllegalStateException("keys"));

        assertThatCode(() -> dispatcher.refresh(9L)).doesNotThrowAnyException();

        verify(sync).sync(9L, GetMoreSyncTrigger.BORROWER_READ);
        verify(sync, never()).sync(9L, GetMoreSyncTrigger.OPERATOR);
    }
}
