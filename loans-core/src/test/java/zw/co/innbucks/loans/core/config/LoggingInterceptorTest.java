package zw.co.innbucks.loans.core.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the shared RestTemplate's interceptor writes to the log, and that writing it
 * never costs the caller the response. Pure JUnit (+ WireMock for the RestConfig
 * wiring), no Spring context; the log is captured straight off Logback.
 */
class LoggingInterceptorTest {

    private static final String PASSWORD = "innbucks-pw-1";
    private static final String API_KEY = "api-key-2";
    private static final String BEARER = "bearer-3";
    private static final String ACCESS_TOKEN = "access-token-4";
    private static final String NATIONAL_ID = "63-123456A63";
    private static final String REQUEST_BODY =
            "{\"username\":\"svc-loans\",\"password\":\"" + PASSWORD + "\",\"idNumber\":\"" + NATIONAL_ID + "\"}";
    private static final String RESPONSE_BODY =
            "{\"responseCode\":\"00\",\"accessToken\":\"" + ACCESS_TOKEN + "\",\"accessExpiry\":\"2026-09-29T12:00\"}";

    private final LoggingInterceptor interceptor = new LoggingInterceptor();
    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(LoggingInterceptor.class);
        originalLevel = logger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    private MockClientHttpRequest request() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://staging.innbucks.co.zw/auth/third-party?api_key=" + API_KEY));
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        request.getHeaders().setBearerAuth(BEARER);
        request.getHeaders().add("X-Api-Key", API_KEY);
        return request;
    }

    /** A response whose body stream can be read ONCE, like an unbuffered request factory's. */
    private static MockClientHttpResponse oneShotResponse(String body, HttpStatus status) {
        MockClientHttpResponse response = new MockClientHttpResponse(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add("Set-Cookie", "SESSION=" + ACCESS_TOKEN);
        return response;
    }

    private String logged() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            all.append(event.getLevel()).append(' ').append(event.getFormattedMessage()).append('\n');
        }
        return all.toString();
    }

    private static String read(ClientHttpResponse response) throws IOException {
        return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
    }

    private static void assertNoSecrets(String log) {
        assertThat(log).doesNotContain(PASSWORD, API_KEY, BEARER, ACCESS_TOKEN, NATIONAL_ID);
    }

    @Test
    @DisplayName("INFO: method, URI, status and duration only — no headers, no bodies, and the body is untouched")
    void infoLogsTheSummaryOnly() throws IOException {
        logger.setLevel(Level.INFO);
        MockClientHttpResponse upstream = oneShotResponse(RESPONSE_BODY, HttpStatus.OK);
        ClientHttpRequestExecution execution = (req, body) -> upstream;

        ClientHttpResponse response = interceptor.intercept(request(),
                REQUEST_BODY.getBytes(StandardCharsets.UTF_8), execution);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent summary = appender.list.get(0);
        assertThat(summary.getLevel()).isEqualTo(Level.INFO);
        assertThat(summary.getFormattedMessage())
                .matches("POST https://staging\\.innbucks\\.co\\.zw/auth/third-party\\?api_key=\\*\\*\\* -> 200 in \\d+ ms");
        assertNoSecrets(logged());
        assertThat(logged()).doesNotContain("svc-loans", "responseCode", "Content-Type");
        // Not even read at INFO: the caller gets the upstream response and its whole body.
        assertThat(response).isSameAs(upstream);
        assertThat(read(response)).isEqualTo(RESPONSE_BODY);
    }

    @Test
    @DisplayName("DEBUG: headers and bodies appear, redacted, and the caller can still read the whole body")
    void debugLogsRedactedDetailAndKeepsTheBodyReadable() throws IOException {
        logger.setLevel(Level.DEBUG);
        ClientHttpRequestExecution execution = (req, body) -> oneShotResponse(RESPONSE_BODY, HttpStatus.OK);

        ClientHttpResponse response = interceptor.intercept(request(),
                REQUEST_BODY.getBytes(StandardCharsets.UTF_8), execution);

        String log = logged();
        assertNoSecrets(log);
        assertThat(log)
                .contains("\"username\":\"svc-loans\"", "\"password\":\"***\"", "\"idNumber\":\"***\"")
                .contains("\"responseCode\":\"00\"", "\"accessToken\":\"***\"")
                .contains("Authorization:\"***\"", "X-Api-Key:\"***\"", "Set-Cookie:\"***\"");
        // The underlying stream was one-shot and the log consumed it, yet the caller reads it whole — twice.
        assertThat(read(response)).isEqualTo(RESPONSE_BODY);
        assertThat(read(response)).isEqualTo(RESPONSE_BODY);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("an error status is a WARN summary; its body is not printed above DEBUG")
    void errorStatusIsAWarnWithoutBody() throws IOException {
        logger.setLevel(Level.INFO);
        String refusal = "{\"error\":\"invalid_grant\",\"echo\":{\"password\":\"" + PASSWORD + "\"}}";
        ClientHttpRequestExecution execution = (req, body) -> oneShotResponse(refusal, HttpStatus.UNAUTHORIZED);

        ClientHttpResponse response = interceptor.intercept(request(), new byte[0], execution);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("-> 401 in").doesNotContain("invalid_grant");
        });
        assertNoSecrets(logged());
        assertThat(read(response)).isEqualTo(refusal);
    }

    @Test
    @DisplayName("a call that fails before any answer is logged as a WARN and the caller gets the same exception")
    void transportFailureIsRethrownUntouched() {
        logger.setLevel(Level.INFO);
        SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");
        ClientHttpRequestExecution execution = (req, body) -> {
            throw timeout;
        };

        assertThatThrownBy(() -> interceptor.intercept(request(),
                REQUEST_BODY.getBytes(StandardCharsets.UTF_8), execution)).isSameAs(timeout);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("failed after").contains("SocketTimeoutException");
        });
        assertNoSecrets(logged());
    }

    @Test
    @DisplayName("an unreadable body at DEBUG is not the interceptor's to throw: the caller gets the response")
    void unreadableBodyNeverThrows() throws IOException {
        logger.setLevel(Level.DEBUG);
        MockClientHttpResponse broken = new MockClientHttpResponse(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        }, HttpStatus.OK);

        ClientHttpResponse response = interceptor.intercept(request(), new byte[0], (req, body) -> broken);

        assertThat(response).isSameAs(broken);
        assertThat(logged()).contains("-> 200 in").contains("body not logged");
    }

    @Test
    @DisplayName("through RestConfig's RestTemplate: the password grant and token are redacted, the caller gets the token")
    void restConfigTemplateStillHandsTheBodyToTheCaller() {
        logger.setLevel(Level.DEBUG);
        WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        // The template comes from RestConfig's bean exactly as the application wires it, whatever
        // collaborators that bean method takes, rather than from a direct call pinned to its signature.
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                RestConfig.class, HttpClientConfig.class, LoggingInterceptor.class)) {
            wireMock.stubFor(post(urlEqualTo("/connect/token")).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"" + ACCESS_TOKEN + "\",\"refresh_token\":\"refresh-5\","
                            + "\"expires_in\":3600}")));
            RestTemplate restTemplate = context.getBean(RestTemplate.class);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "password");
            form.add("username", "svc-ndasenda");
            form.add("password", PASSWORD);
            form.add("security_code", "security-code-6");

            ResponseEntity<Map> response = restTemplate.exchange(wireMock.baseUrl() + "/connect/token",
                    HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);

            assertThat(response.getBody()).containsEntry("access_token", ACCESS_TOKEN);
            String log = logged();
            assertNoSecrets(log);
            assertThat(log).doesNotContain("refresh-5", "security-code-6")
                    .contains("grant_type=password", "username=svc-ndasenda", "password=***", "security_code=***")
                    .contains("\"access_token\":\"***\"", "\"expires_in\":3600")
                    .containsPattern("INFO POST http://localhost:\\d+/connect/token -> 200 in \\d+ ms");
        } finally {
            wireMock.stop();
        }
    }

    @Test
    @DisplayName("nothing is logged at DEBUG when DEBUG is off")
    void noDebugEventsAtInfo() throws IOException {
        logger.setLevel(Level.INFO);

        interceptor.intercept(request(), REQUEST_BODY.getBytes(StandardCharsets.UTF_8),
                (req, body) -> oneShotResponse(RESPONSE_BODY, HttpStatus.OK));

        List<Level> levels = appender.list.stream().map(ILoggingEvent::getLevel).toList();
        assertThat(levels).containsOnly(Level.INFO);
    }
}
