package zw.co.innbucks.loans.core.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shared RestTemplate carries every Ndasenda and InnBucks call, including the irreversible
 * writes: an InnBucks booking or deposit and a Ndasenda lodgement. Pins, on the wire:
 * <ul>
 *   <li>a POST whose connection dies before any response is sent ONCE, never re-sent by the
 *       transport;</li>
 *   <li>the timeouts come from {@link HttpClientConfig}, and a slow upstream is cut off;</li>
 *   <li>each failure keeps the exception type the callers classify on: a refused or connect-timed-out
 *       call reads as never sent, a read timeout or a reset as possibly sent, and a 4xx/5xx as an
 *       HTTP error (a 401 included, which drives the token refresh) whose body can be read;</li>
 *   <li>the buffering wrapper and the logging interceptor are still in place.</li>
 * </ul>
 *
 * <p>Pure JUnit + standalone WireMock; the one Spring piece is an {@link ApplicationContextRunner},
 * because CI never boots the application context and the property binding would otherwise go
 * untested.
 */
class RestConfigTest {

    private static final String SLOW = "/slow";
    private static final String FAST = "/fast";
    private static final String FAULT = "/fault";
    private static final String JSON = "/json";
    private static final String BAD_REQUEST = "/bad-request";
    private static final String UNAUTHORIZED = "/unauthorized";
    private static final String SERVER_ERROR = "/server-error";

    private static final String REFUSAL = "{\"responseCode\":12,\"responseMsg\":\"Invalid destination\"}";

    /** Far longer than the read timeouts used here, so a pass cannot be the answer arriving. */
    private static final int UPSTREAM_DELAY_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 300;

    private static WireMockServer wireMock;

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
    void stubs() {
        wireMock.resetAll();
        // POSTs, like the booking, deposit and lodgement calls these failures matter most for.
        wireMock.stubFor(post(urlEqualTo(SLOW))
                .willReturn(okJson("{\"late\":true}").withFixedDelay(UPSTREAM_DELAY_MS)));
        wireMock.stubFor(post(urlEqualTo(JSON)).willReturn(okJson("{\"ok\":true}")));
        wireMock.stubFor(post(urlEqualTo(BAD_REQUEST)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody(REFUSAL)));
        wireMock.stubFor(post(urlEqualTo(UNAUTHORIZED)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"token expired\"}")));
        wireMock.stubFor(post(urlEqualTo(SERVER_ERROR)).willReturn(aResponse().withStatus(502)
                .withHeader("Content-Type", "text/plain").withBody("upstream unavailable")));
        wireMock.stubFor(get(urlEqualTo(FAST)).willReturn(okJson("{\"ok\":true}")));
    }

    private static HttpClientConfig timeouts(int connectMs, int readMs) {
        HttpClientConfig config = new HttpClientConfig();
        config.setConnectTimeout(connectMs);
        config.setReadTimeout(readMs);
        return config;
    }

    private static RestTemplate restTemplate(int connectMs, int readMs) {
        return new RestConfig().restTemplate(new LoggingInterceptor(), timeouts(connectMs, readMs));
    }

    private static String url(String path) {
        return "http://localhost:" + wireMock.port() + path;
    }

    private static List<Throwable> causeChain(Throwable thrown) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = thrown; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    /** Asserts the call gives up on a read timeout, well before the upstream would have answered. */
    private static void assertCutOffAtReadTimeout(RestTemplate restTemplate) {
        long started = System.nanoTime();
        Throwable thrown = catchThrowable(() -> restTemplate.postForEntity(url(SLOW), "{}", String.class));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        // A read timeout, not a connect one: the request may have been acted on.
        assertThat(causeChain(thrown))
                .anyMatch(t -> t instanceof SocketTimeoutException && "Read timed out".equals(t.getMessage()))
                .noneMatch(RestConfigTest::isConnectTimeout)
                .noneMatch(ConnectException.class::isInstance);
        assertThat(took).isLessThan(Duration.ofMillis(UPSTREAM_DELAY_MS - 1_000));
    }

    /** HttpURLConnection's connect timeout: "Connect timed out", with a capital C. */
    private static boolean isConnectTimeout(Throwable t) {
        return t instanceof SocketTimeoutException && "Connect timed out".equals(t.getMessage());
    }

    /** InterceptingClientHttpRequestFactory -> BufferingClientHttpRequestFactory -> SimpleClientHttpRequestFactory. */
    private static SimpleClientHttpRequestFactory innermostFactory(RestTemplate restTemplate) {
        ClientHttpRequestFactory intercepting = restTemplate.getRequestFactory();
        assertThat(intercepting).isInstanceOf(InterceptingClientHttpRequestFactory.class);
        ClientHttpRequestFactory buffering = ((AbstractClientHttpRequestFactoryWrapper) intercepting).getDelegate();
        assertThat(buffering).isInstanceOf(BufferingClientHttpRequestFactory.class);
        ClientHttpRequestFactory simple = ((AbstractClientHttpRequestFactoryWrapper) buffering).getDelegate();
        assertThat(simple).isInstanceOf(SimpleClientHttpRequestFactory.class);
        return (SimpleClientHttpRequestFactory) simple;
    }

    private static void assertTimeouts(RestTemplate restTemplate, int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = innermostFactory(restTemplate);
        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(connectMs);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(readMs);
    }

    @Test
    @DisplayName("the configured connect and read timeouts reach the request factory, under the buffering wrapper")
    void appliesTheConfiguredTimeouts() {
        assertTimeouts(restTemplate(1_234, 5_678), 1_234, 5_678);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
    @DisplayName("a POST whose connection dies before any response is sent once, never re-sent")
    void postIsNeverReSentAfterTheConnectionDies(Fault fault) {
        wireMock.stubFor(post(urlEqualTo(FAULT)).willReturn(aResponse().withFault(fault)));

        Throwable thrown = catchThrowable(() -> restTemplate(1_000, 5_000)
                .postForEntity(url(FAULT), "{\"amount\":150000}", String.class));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        // The request left, so this must not read as a failure to connect.
        assertThat(causeChain(thrown))
                .noneMatch(ConnectException.class::isInstance)
                .noneMatch(SocketTimeoutException.class::isInstance);
        // HttpURLConnection re-sends a POST like this unless it is streamed; see RestConfig.
        wireMock.verify(1, postRequestedFor(urlEqualTo(FAULT)));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
    @DisplayName("a POST with no body (the Ndasenda batch commit) is not re-sent either")
    void emptyPostIsNeverReSentAfterTheConnectionDies(Fault fault) {
        wireMock.stubFor(post(urlEqualTo(FAULT)).willReturn(aResponse().withFault(fault)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Throwable thrown = catchThrowable(() -> restTemplate(1_000, 5_000)
                .exchange(url(FAULT), HttpMethod.POST, new HttpEntity<>(headers), String.class));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        wireMock.verify(1, postRequestedFor(urlEqualTo(FAULT)));
    }

    @Test
    @DisplayName("an upstream slower than the read timeout fails fast, once, as a read timeout")
    void slowUpstreamIsCutOffAtTheReadTimeout() {
        assertCutOffAtReadTimeout(restTemplate(1_000, READ_TIMEOUT_MS));
        wireMock.verify(1, postRequestedFor(urlEqualTo(SLOW)));
    }

    @Test
    @DisplayName("a refused connection surfaces as a ConnectException: never sent")
    void refusedConnectionIsAConnectException() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        Throwable thrown = catchThrowable(() -> restTemplate(1_000, 5_000)
                .postForEntity("http://localhost:" + closedPort + JSON, "{}", String.class));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(thrown)).anyMatch(ConnectException.class::isInstance);
    }

    @Test
    @DisplayName("a connect that hangs is cut off at the connect timeout, as SocketTimeoutException(\"Connect timed out\")")
    void hangingConnectIsAConnectTimeout() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        // A listener nobody accepts from: once its backlog is full, the kernel drops further SYNs,
        // so the next connect hangs until a timeout ends it.
        try (ServerSocket neverAccepts = new ServerSocket(0, 1, loopback)) {
            InetSocketAddress address = new InetSocketAddress(loopback, neverAccepts.getLocalPort());
            List<Socket> queued = new ArrayList<>();
            try {
                boolean backlogFull = false;
                for (int i = 0; i < 16 && !backlogFull; i++) {
                    Socket socket = new Socket();
                    queued.add(socket);
                    try {
                        socket.connect(address, 200);
                    } catch (SocketTimeoutException timedOut) {
                        backlogFull = true;
                    }
                }
                assumeTrue(backlogFull, "this OS keeps accepting handshakes past the listen backlog");

                long started = System.nanoTime();
                Throwable thrown = catchThrowable(() -> restTemplate(500, 5_000)
                        .postForEntity("http://127.0.0.1:" + neverAccepts.getLocalPort() + JSON, "{}", String.class));
                Duration took = Duration.ofNanos(System.nanoTime() - started);

                assertThat(thrown).isInstanceOf(ResourceAccessException.class);
                assertThat(causeChain(thrown)).anyMatch(RestConfigTest::isConnectTimeout);
                assertThat(took).isLessThan(Duration.ofMillis(4_000));
            } finally {
                for (Socket socket : queued) {
                    socket.close();
                }
            }
        }
    }

    @Test
    @DisplayName("a 400 is an HttpClientErrorException whose body can still be read")
    void badRequestBodyIsReadable() {
        Throwable thrown = catchThrowable(() -> restTemplate(1_000, 5_000)
                .postForEntity(url(BAD_REQUEST), "{}", String.class));

        assertThat(thrown).isInstanceOf(HttpClientErrorException.BadRequest.class);
        HttpClientErrorException error = (HttpClientErrorException) thrown;
        assertThat(error.getResponseBodyAsString()).isEqualTo(REFUSAL);
        // InnbucksDisbursementService.classifyClientError reads InnBucks' own refusal this way.
        assertThat(error.getResponseBodyAs(Map.class)).containsEntry("responseCode", 12);
    }

    @Test
    @DisplayName("a 401 is raised, not swallowed or retried, so the caller's one token refresh still runs")
    void unauthorizedIsRaised() {
        Throwable thrown = catchThrowable(() -> restTemplate(1_000, 5_000)
                .postForEntity(url(UNAUTHORIZED), "{}", String.class));

        // Its body is not asserted: a 401 answering a streamed POST arrives without one (RestConfig).
        assertThat(thrown).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        wireMock.verify(1, postRequestedFor(urlEqualTo(UNAUTHORIZED)));
    }

    @Test
    @DisplayName("a 5xx is an HttpServerErrorException whose body can still be read")
    void serverErrorBodyIsReadable() {
        Throwable thrown = catchThrowable(() -> restTemplate(1_000, 5_000)
                .postForEntity(url(SERVER_ERROR), "{}", String.class));

        assertThat(thrown).isInstanceOf(HttpServerErrorException.class);
        assertThat(((HttpServerErrorException) thrown).getResponseBodyAsString()).isEqualTo("upstream unavailable");
    }

    @Test
    @DisplayName("the logging interceptor is still the one interceptor, and a prompt answer still reads through it")
    void loggingInterceptorIsStillInstalled() {
        LoggingInterceptor loggingInterceptor = new LoggingInterceptor();
        RestTemplate restTemplate = new RestConfig().restTemplate(loggingInterceptor, timeouts(1_000, READ_TIMEOUT_MS));

        assertThat(restTemplate.getInterceptors()).containsExactly(loggingInterceptor);

        // The interceptor consumes the response body to log it; the buffering wrapper is what
        // leaves it readable for the caller.
        ResponseEntity<String> response = restTemplate.getForEntity(url(FAST), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("an unconfigured HttpClientConfig still bounds every call")
    void defaultsAreBounded() {
        HttpClientConfig defaults = new HttpClientConfig();

        assertThat(defaults.getConnectTimeout()).isEqualTo(10_000);
        assertThat(defaults.getReadTimeout()).isEqualTo(60_000);
    }

    @Test
    @DisplayName("a zero or negative timeout is refused when the bean is built")
    void refusesNonPositiveTimeouts() {
        assertThatThrownBy(() -> restTemplate(0, 60_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http.client.connect-timeout");
        assertThatThrownBy(() -> restTemplate(10_000, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http.client.read-timeout");
        assertThatThrownBy(() -> restTemplate(10_000, -1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http.client.read-timeout");
    }

    // --- Wiring: http.client.* -> the one HttpClientConfig bean -> the RestTemplate ---

    /** How LoansCoreConfig registers the properties. */
    @EnableConfigurationProperties(HttpClientConfig.class)
    static class RegisteredLikeLoansCoreConfig {
    }

    /**
     * What the application's component scan finds in HttpClientConfig's own class file: nothing, as
     * long as it is not a stereotype. Were it a @Configuration again, this would add a second bean of
     * the type and the single-bean assertion below would fail.
     */
    @ComponentScan(basePackageClasses = HttpClientConfig.class, resourcePattern = "HttpClientConfig.class")
    static class ScannedLikeTheApplication {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RegisteredLikeLoansCoreConfig.class, ScannedLikeTheApplication.class,
                    RestConfig.class, LoggingInterceptor.class);

    @Test
    @DisplayName("http.client.* properties bind to one HttpClientConfig and time out the RestTemplate bean")
    void propertiesBindIntoTheRestTemplateBean() {
        contextRunner
                .withPropertyValues("http.client.connect-timeout=1234",
                        "http.client.read-timeout=" + READ_TIMEOUT_MS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HttpClientConfig.class);
                    assertThat(context).hasSingleBean(RestTemplate.class);

                    RestTemplate restTemplate = context.getBean(RestTemplate.class);
                    assertTimeouts(restTemplate, 1_234, READ_TIMEOUT_MS);
                    assertThat(restTemplate.getInterceptors())
                            .containsExactly(context.getBean(LoggingInterceptor.class));

                    assertCutOffAtReadTimeout(restTemplate);
                });
    }

    @Test
    @DisplayName("with no http.client properties the bean falls back to the bounded defaults, never to none")
    void noPropertiesMeansTheDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertTimeouts(context.getBean(RestTemplate.class), 10_000, 60_000);
        });
    }

    @Test
    @DisplayName("a zero timeout in configuration fails the context instead of reinstating the hang")
    void zeroInConfigurationFailsTheContext() {
        contextRunner
                .withPropertyValues("http.client.read-timeout=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("http.client.read-timeout"));
    }
}
