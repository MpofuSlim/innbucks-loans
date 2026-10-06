package zw.co.innbucks.loans.core.notifications;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import zw.co.innbucks.loans.core.testsupport.TestOutboundHttp;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The notification login under concurrency, against a real HTTP login: a slow login is shared by every
 * sender that needs it, and a burst of 401s on one token costs one login. Pure JUnit + WireMock.
 */
class NotificationApiAuthenticatorConcurrencyTest {

    private static final String LOGIN = "/auth/third-party";
    private static final int SENDERS = 8;

    private static WireMockServer wireMock;
    private final ExecutorService pool = Executors.newFixedThreadPool(SENDERS);
    private NotificationApiAuthenticator authenticator;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void setup() {
        wireMock.resetAll();
        InnbucksNotifyProperties props = new InnbucksNotifyProperties();
        props.setBaseUrl("http://localhost:" + wireMock.port());
        props.setApiKey("test-api-key");
        props.setUsername("test-user");
        props.setPassword("test-pass");
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(5000);
        authenticator = new NotificationApiAuthenticator(
                new InnbucksNotifyClientConfig().innbucksNotifyRestClient(props, TestOutboundHttp.POOL), props);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("senders arriving during a slow login share it: one login")
    void concurrentColdSenders_shareOneLogin() throws Exception {
        wireMock.stubFor(post(urlEqualTo(LOGIN))
                .willReturn(okJson("{\"accessToken\":\"tok-1\"}").withFixedDelay(500)));

        List<String> used = runConcurrently(token -> token);

        assertThat(used).hasSize(SENDERS).containsOnly("tok-1");
        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("a burst of 401s on the same token costs one new login, and every sender replays with it")
    void concurrent401s_causeOneLogin() throws Exception {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("login")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"accessToken\":\"tok-1\"}"))
                .willSetStateTo("second"));
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("login")
                .whenScenarioStateIs("second")
                .willReturn(okJson("{\"accessToken\":\"tok-2\"}").withFixedDelay(300)));
        authenticator.withAuthRetryOn401(token -> token); // warm: tok-1 is cached

        CountDownLatch allRefused = new CountDownLatch(SENDERS);
        List<String> used = runConcurrently(token -> {
            if (token.equals("tok-1")) {
                // Every sender is refused before any of them refreshes, so the refreshes overlap.
                allRefused.countDown();
                await(allRefused);
                throw new NotificationApiAuthenticator.UnauthorizedException();
            }
            return token;
        });

        assertThat(used).hasSize(SENDERS).containsOnly("tok-2");
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    private List<String> runConcurrently(java.util.function.Function<String, String> call) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < SENDERS; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return authenticator.withAuthRetryOn401(call);
            }));
        }
        go.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        return results;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("senders never all reached the 401");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
