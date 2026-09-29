package zw.co.reikan.loans.core.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared RestTemplate carries every Ndasenda and InnBucks call, and all
 * {@code @Scheduled} jobs share one scheduler thread; a call with no timeout
 * therefore froze every job. Pins that the bean {@link RestConfig} builds takes
 * its connect/read timeouts from {@link HttpClientConfig}, that a slow upstream
 * is cut off at the read timeout, and that the buffering wrapper and the
 * logging interceptor are still in place.
 *
 * <p>Pure JUnit + standalone WireMock; the one Spring piece is an
 * {@link ApplicationContextRunner}, because CI never boots the application
 * context and the property binding would otherwise go untested.
 */
class RestConfigTest {

    private static final String SLOW = "/slow";
    private static final String FAST = "/fast";

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
        // A POST, like the booking and deposit calls a read timeout matters most for.
        wireMock.stubFor(post(urlEqualTo(SLOW))
                .willReturn(okJson("{\"late\":true}").withFixedDelay(UPSTREAM_DELAY_MS)));
        wireMock.stubFor(get(urlEqualTo(FAST))
                .willReturn(okJson("{\"ok\":true}")));
    }

    private static HttpClientConfig timeouts(int connectMs, int readMs) {
        HttpClientConfig config = new HttpClientConfig();
        config.setConnectTimeout(connectMs);
        config.setReadTimeout(readMs);
        return config;
    }

    private static String url(String path) {
        return "http://localhost:" + wireMock.port() + path;
    }

    /** Asserts the call gives up on a read timeout, well before the upstream would have answered. */
    private static void assertCutOffAtReadTimeout(RestTemplate restTemplate) {
        long started = System.nanoTime();
        assertThatThrownBy(() -> restTemplate.postForEntity(url(SLOW), "{}", String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(took).isLessThan(Duration.ofMillis(UPSTREAM_DELAY_MS - 1_000));
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

    @Test
    @DisplayName("the configured connect and read timeouts reach the request factory, under the buffering wrapper")
    void appliesTheConfiguredTimeouts() {
        RestTemplate restTemplate = new RestConfig().restTemplate(new LoggingInterceptor(), timeouts(1_234, 5_678));

        SimpleClientHttpRequestFactory factory = innermostFactory(restTemplate);
        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(1_234);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(5_678);
    }

    @Test
    @DisplayName("an upstream slower than the read timeout fails fast instead of hanging the caller")
    void slowUpstreamIsCutOffAtTheReadTimeout() {
        RestTemplate restTemplate = new RestConfig().restTemplate(new LoggingInterceptor(),
                timeouts(1_000, READ_TIMEOUT_MS));

        assertCutOffAtReadTimeout(restTemplate);
        // Sent once: the transport does not quietly re-send a write whose answer timed out.
        wireMock.verify(1, postRequestedFor(urlEqualTo(SLOW)));
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
    @DisplayName("a zero or negative timeout is refused at boot: to HttpURLConnection 0 means wait forever")
    void refusesNonPositiveTimeouts() {
        assertThatThrownBy(() -> new RestConfig().restTemplate(new LoggingInterceptor(), timeouts(0, 60_000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http.client.connect-timeout");
        assertThatThrownBy(() -> new RestConfig().restTemplate(new LoggingInterceptor(), timeouts(10_000, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http.client.read-timeout");
        assertThatThrownBy(() -> new RestConfig().restTemplate(new LoggingInterceptor(), timeouts(10_000, -1)))
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
                    SimpleClientHttpRequestFactory factory = innermostFactory(restTemplate);
                    assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(1_234);
                    assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(READ_TIMEOUT_MS);
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
            SimpleClientHttpRequestFactory factory = innermostFactory(context.getBean(RestTemplate.class));
            assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(10_000);
            assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(60_000);
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
