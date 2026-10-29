package zw.co.innbucks.loans.core.disbursements;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import zw.co.innbucks.loans.core.config.HttpClientConfig;
import zw.co.innbucks.loans.core.testsupport.TestOutboundHttp;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The InnBucks login on the wire, and its token kept single-flight ({@code SingleFlightTokenCache}): the apply, the
 * inquiry and the deposit present it, and the Staff Grocery Loan payout, its status check and the operators' calls
 * reach it concurrently. It replaces the {@code @Cacheable} token under which two callers missing the cache together
 * each logged in, and a 2xx login without a token came back as {@code null} and went out as "Bearer null".
 */
class InnbucksAuthServiceTest {

    private static final String LOGIN = "/auth/third-party";
    private static final Instant T0 = Instant.parse("2026-10-29T08:00:00Z");

    private static WireMockServer wireMock;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private InnbucksParameters params;
    private RestTemplate restTemplate;

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
        now.set(T0);
        params = new InnbucksParameters();
        params.setApiKey("test-api-key");
        params.setUsername("test-user");
        params.setPassword("test-pass");
        params.setAuthEndpoint("http://localhost:" + wireMock.port() + LOGIN);
        restTemplate = new RestTemplate(TestOutboundHttp.POOL.requestFactory(500, 2000));
    }

    private InnbucksAuthService auth() {
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        return new InnbucksAuthService(restTemplate, params, new HttpClientConfig(), clock);
    }

    /** Each login answers the next token of {@code tokens}, the last one again once they run out. */
    private static void loginsAnswer(String... tokens) {
        for (int i = 0; i < tokens.length; i++) {
            String state = i == 0 ? STARTED : "login-" + i;
            wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("logins").whenScenarioStateIs(state)
                    .willReturn(okJson("{\"accessToken\":\"" + tokens[i] + "\"}"))
                    .willSetStateTo(i + 1 < tokens.length ? "login-" + (i + 1) : state));
        }
    }

    private static String jwtExpiringAt(Instant exp) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(("{\"exp\":" + exp.getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8))
                + ".c2ln";
    }

    @Test
    @DisplayName("the login: POST /auth/third-party, X-Api-Key, a trace id, username and password; no trace context")
    void loginIsPinned() {
        loginsAnswer("tok-1");

        assertThat(auth().getAccessToken()).isEqualTo("tok-1");

        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("X-Api-Key", equalTo("test-api-key"))
                .withHeader("X-Trace-Id", matching("[0-9a-f-]{36}"))
                .withoutHeader("traceparent")
                .withRequestBody(matchingJsonPath("$.username", equalTo("test-user")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("test-pass"))));
    }

    @Test
    @DisplayName("the token is kept: a second call logs in no more")
    void theTokenIsKept() {
        loginsAnswer("tok-1", "tok-2");
        InnbucksAuthService auth = auth();

        assertThat(auth.getAccessToken()).isEqualTo("tok-1");
        assertThat(auth.getAccessToken()).isEqualTo("tok-1");

        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("8 callers with no token at once: ONE login, and every one of them gets its token")
    void concurrentCallersShareOneLogin() throws Exception {
        wireMock.stubFor(post(urlEqualTo(LOGIN))
                .willReturn(okJson("{\"accessToken\":\"tok-1\"}").withFixedDelay(300)));
        InnbucksAuthService auth = auth();

        List<String> tokens = runTogether(8, auth::getAccessToken);

        assertThat(tokens).hasSize(8).containsOnly("tok-1");
        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("N concurrent 401s on the same token cost ONE login, and all of them get the new token")
    void concurrentRejectionsShareOneLogin() throws Exception {
        loginsAnswer("tok-1");
        InnbucksAuthService auth = auth();
        assertThat(auth.getAccessToken()).isEqualTo("tok-1");
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(LOGIN))
                .willReturn(okJson("{\"accessToken\":\"tok-2\"}").withFixedDelay(300)));

        List<String> tokens = runTogether(6, () -> auth.refreshAfterRejection("tok-1"));

        assertThat(tokens).containsOnly("tok-2");
        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("a 401 on a token already replaced logs in no more: the newer token is the answer")
    void aStaleRejectionReusesTheNewerToken() {
        loginsAnswer("tok-1", "tok-2", "tok-3");
        InnbucksAuthService auth = auth();
        assertThat(auth.getAccessToken()).isEqualTo("tok-1");
        assertThat(auth.refreshAfterRejection("tok-1")).isEqualTo("tok-2");

        assertThat(auth.refreshAfterRejection("tok-1")).as("someone else's 401 on the old token")
                .isEqualTo("tok-2");

        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("a failed login caches nothing: the refused token is never handed out, the next call logs in")
    void aFailedLoginCachesNothing() {
        loginsAnswer("tok-1");
        InnbucksAuthService auth = auth();
        assertThat(auth.getAccessToken()).isEqualTo("tok-1");
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(503)
                .withBody("{\"password\":\"echoed-secret\"}")));

        assertThatThrownBy(() -> auth.refreshAfterRejection("tok-1"))
                .isInstanceOf(InnbucksLoginException.class)
                .hasMessage("InnBucks login failed: HTTP 503")
                .hasMessageNotContaining("echoed-secret");

        wireMock.resetAll();
        loginsAnswer("tok-2");
        assertThat(auth.getAccessToken()).as("never the refused tok-1").isEqualTo("tok-2");
    }

    @Test
    @DisplayName("a 2xx login without an accessToken is a failed login, never \"Bearer null\"")
    void aLoginWithoutATokenFails() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"responseCode\":\"000\"}")));
        InnbucksAuthService auth = auth();

        assertThatThrownBy(auth::getAccessToken).isInstanceOf(InnbucksLoginException.class)
                .hasMessageContaining("without an accessToken");

        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"  \"}")));
        assertThatThrownBy(auth::getAccessToken).isInstanceOf(InnbucksLoginException.class);
    }

    @Test
    @DisplayName("an unreachable login is an InnbucksLoginException too")
    void anUnreachableLoginFails() {
        params.setAuthEndpoint("http://localhost:1" + LOGIN);

        assertThatThrownBy(() -> auth().getAccessToken()).isInstanceOf(InnbucksLoginException.class)
                .hasMessageStartingWith("Could not log in to InnBucks");
    }

    @Test
    @DisplayName("an opaque token is used for innbucks.token-ttl, less the last 30 s, then a refresh starts")
    void anOpaqueTokenLivesForTheTtl() {
        params.setTokenTtl(Duration.ofMinutes(5));
        loginsAnswer("tok-1", "tok-2");
        InnbucksAuthService auth = auth();
        assertThat(auth.getAccessToken()).isEqualTo("tok-1");

        // Used until 4m30s; a refresh starts a minute before that.
        now.set(T0.plus(Duration.ofMinutes(3).plusSeconds(29)));
        assertThat(auth.getAccessToken()).isEqualTo("tok-1");
        now.set(T0.plus(Duration.ofMinutes(3).plusSeconds(31)));
        assertThat(auth.getAccessToken()).isEqualTo("tok-2");
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("a JWT's own exp is honoured over the TTL")
    void aJwtExpIsHonoured() {
        params.setTokenTtl(Duration.ofMinutes(5));
        String jwt = jwtExpiringAt(T0.plus(Duration.ofMinutes(30)));
        loginsAnswer(jwt, "tok-2");
        InnbucksAuthService auth = auth();
        assertThat(auth.getAccessToken()).isEqualTo(jwt);

        // Well past the TTL, inside the token's own life (30 min, less 30 s, less the 60 s margin).
        now.set(T0.plus(Duration.ofMinutes(28).plusSeconds(29)));
        assertThat(auth.getAccessToken()).isEqualTo(jwt);
        now.set(T0.plus(Duration.ofMinutes(28).plusSeconds(31)));
        assertThat(auth.getAccessToken()).isEqualTo("tok-2");
    }

    @Test
    @DisplayName("a token-ttl too short to be reused refuses to start")
    void aTtlTooShortIsRefused() {
        params.setTokenTtl(Duration.ofSeconds(60));

        assertThatThrownBy(this::auth).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("innbucks.token-ttl");
    }

    /** Runs {@code call} on {@code threads} threads released together; their answers. */
    private static List<String> runTogether(int threads, Callable<String> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> answers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<String> tokens = new ArrayList<>();
            for (Future<String> answer : answers) {
                tokens.add(answer.get(10, TimeUnit.SECONDS));
            }
            return tokens;
        } finally {
            pool.shutdownNow();
        }
    }
}
