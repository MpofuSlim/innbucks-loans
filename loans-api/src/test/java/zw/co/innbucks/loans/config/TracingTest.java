package zw.co.innbucks.loans.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import zw.co.innbucks.loans.core.config.HttpClientConfig;
import zw.co.innbucks.loans.core.config.LoggingInterceptor;
import zw.co.innbucks.loans.core.config.OutboundHttp;
import zw.co.innbucks.loans.core.config.RestConfig;
import zw.co.innbucks.loans.core.notifications.WhatsAppClientConfig;
import zw.co.innbucks.loans.core.notifications.WhatsAppNotificationClient;
import zw.co.innbucks.loans.core.notifications.WhatsAppProperties;
import zw.co.innbucks.loans.security.ApiSecurityConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Distributed tracing with loans' packaged {@code application.yml}, the real Boot tracing auto-configuration and the
 * real {@link TracingConfig}, over real HTTP (fleet convention, CLAUDE.md "Tracing"): the gateway's
 * {@code traceparent} is continued into the log MDC, partners — built by loans' OWN client factories — never receive
 * it, the propagation guard admits only a fleet Service name, {@code @Async} keeps the trace without carrying the
 * caller's authentication, and with no endpoint there is no exporter.
 *
 * <p>The database, JPA and Flyway are switched off as in {@code PrometheusEndpointTest}: tracing and its wiring are
 * what is under test, not the application's beans.
 */
@SpringBootTest(classes = TracingTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoint.health.validate-group-membership=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
        })
class TracingTest {

    private static final String INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INCOMING_SPAN_ID = "00f067aa0ba902b7";
    private static final String PROBE_LOGGER = "tracing-probe";

    private static final WireMockServer WIREMOCK = startedWireMock();
    /** The production transport the partner clients are built on (config/OutboundHttp). */
    private static final OutboundHttp POOL = OutboundHttp.withDefaults();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableAsync
    @Import({ApiSecurityConfig.class, TracingConfig.class})
    static class App {

        /** No loans token is valid here; the request only has to pass through the filters. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("not in this test");
            };
        }

        /**
         * Logs one line from INSIDE request handling (after the observation filter, before security), the way any
         * service code would.
         */
        @Bean
        FilterRegistrationBean<Filter> tracingProbeFilter() {
            org.slf4j.Logger log = LoggerFactory.getLogger(PROBE_LOGGER);
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                log.info("probe");
                chain.doFilter(request, response);
            });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }

        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    /** An @Async method on the default executor, which is what every loans @Async uses. */
    static class AsyncProbe {
        @Async
        public CompletableFuture<String> traceIdOnWorker() {
            return CompletableFuture.completedFuture(MDC.get("traceId"));
        }

        @Async
        public CompletableFuture<Object> authenticationOnWorker() {
            return CompletableFuture.completedFuture(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    @Autowired private ObservationRegistry observationRegistry;
    @Autowired private Tracer tracer;
    @Autowired private AsyncProbe asyncProbe;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final ListAppender<ILoggingEvent> probeLog = new ListAppender<>();

    @BeforeEach
    void stubPartnersAndCaptureTheProbe() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        probeLog.start();
        ((Logger) LoggerFactory.getLogger(PROBE_LOGGER)).addAppender(probeLog);
    }

    @AfterEach
    void stopCapturing() {
        ((Logger) LoggerFactory.getLogger(PROBE_LOGGER)).detachAppender(probeLog);
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void stopWireMock() throws java.io.IOException {
        WIREMOCK.stop();
        POOL.close();
    }

    // ---- incoming requests + the log MDC ----------------------------------

    @Test
    void theGatewaysTraceparent_isContinued_andItsIdsAreInTheLogMdc() throws Exception {
        HttpResponse<String> response = http.send(java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/lending/v1/probe"))
                .header("traceparent", "00-" + INCOMING_TRACE_ID + "-" + INCOMING_SPAN_ID + "-01")
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        ILoggingEvent line = probeLine();
        assertThat(line.getMDCPropertyMap()).containsEntry("traceId", INCOMING_TRACE_ID);
        assertThat(line.getMDCPropertyMap().get("spanId"))
                .matches("[0-9a-f]{16}")
                .isNotEqualTo(INCOMING_SPAN_ID);
    }

    @Test
    void aRequestWithNoTraceparent_startsATrace() throws Exception {
        http.send(java.net.http.HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/lending/v1/probe")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(probeLine().getMDCPropertyMap().get("traceId")).matches("[0-9a-f]{32}");
    }

    @Test
    void thePackagedLogPattern_printsTheTraceIds() throws Exception {
        String logback = new String(new org.springframework.core.io.ClassPathResource("logback.xml")
                .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(logback).contains("[%X{traceId:-},%X{spanId:-}]");
    }

    // ---- outbound: partners never, a fleet name only ----------------------

    @Test
    void theWhatsAppGateway_builtByLoansOwnFactory_neverGetsTheTrace() {
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.setBaseUrl("http://localhost:" + WIREMOCK.port());
        properties.setApiKey("test-whatsapp-key");
        WhatsAppNotificationClient whatsApp = new WhatsAppNotificationClient(
                new WhatsAppClientConfig().whatsAppRestClient(properties, POOL), properties);

        inObservation(() -> whatsApp.sendCustomNotification("+263771234567", "Hello"));

        WIREMOCK.verify(postRequestedFor(urlEqualTo("/api/messages/custom-notification"))
                .withoutHeader("traceparent")
                .withoutHeader("tracestate")
                .withoutHeader("baggage"));
    }

    @Test
    void theSharedInnbucksAndNdasendaRestTemplate_neverSendsTheTrace() {
        HttpClientConfig timeouts = new HttpClientConfig();
        RestTemplate restTemplate = new RestConfig().restTemplate(
                new LoggingInterceptor(), timeouts, POOL);

        inObservation(() -> restTemplate.postForEntity(
                "http://localhost:" + WIREMOCK.port() + "/bank/api/deposit", "{}", String.class));

        WIREMOCK.verify(postRequestedFor(urlEqualTo("/bank/api/deposit")).withoutHeader("traceparent"));
    }

    @Test
    void evenAnObservedClient_sendsNoTrace_toANonFleetHost() {
        RestClient observed = RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl("http://localhost:" + WIREMOCK.port())
                .build();

        inObservation(() -> observed.get().uri("/partner/ping").retrieve().toBodilessEntity());

        WIREMOCK.verify(1, anyRequestedFor(urlEqualTo("/partner/ping")));
        WIREMOCK.verify(anyRequestedFor(urlEqualTo("/partner/ping")).withoutHeader("traceparent"));
    }

    @Test
    void aFleetServiceName_doesGetTheTrace() {
        // Loans calls no fleet service today. This proves the guard is not simply "never": a request addressed to
        // a Service name carries the trace. The interceptor stands in for cluster DNS (it runs at EXECUTE, after
        // the trace was injected against the name, exactly like a LoadBalancer would).
        RestClient fleet = RestClient.builder()
                .observationRegistry(observationRegistry)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .baseUrl("http://user-service:8081")
                .requestInterceptor((request, body, execution) -> execution.execute(
                        new RedirectedRequest(request, WIREMOCK.port()), body))
                .build();

        String traceId = inObservation(() -> fleet.get().uri("/users/internal/ping").retrieve().toBodilessEntity());

        WIREMOCK.verify(anyRequestedFor(urlEqualTo("/users/internal/ping"))
                .withHeader("traceparent", matching("00-" + traceId + "-[0-9a-f]{16}-[0-9a-f]{2}")));
    }

    @Test
    void theSenderHandlerInContext_isTheFleetOnlyOne() {
        assertThat(context.getBeansOfType(PropagatingSenderTracingObservationHandler.class)).hasSize(1);
        assertThat(context.getBean(Tracer.class)).isInstanceOf(OtelTracer.class);
    }

    @Test
    void withNoEndpointConfigured_noExporterExists() {
        assertThat(context.getBeansOfType(OtlpTracingConnectionDetails.class)).isEmpty();
        assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
    }

    // ---- @Async ------------------------------------------------------------

    @Test
    void anAsyncMethod_keepsTheTrace() throws Exception {
        AtomicReference<CompletableFuture<String>> onWorker = new AtomicReference<>();
        String traceId = inObservation(() -> onWorker.set(asyncProbe.traceIdOnWorker()));

        assertThat(onWorker.get().get(10, TimeUnit.SECONDS)).isEqualTo(traceId);
    }

    @Test
    void theHandOff_carriesOnlyTheTrace_notTheCallersAuthentication() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("caller", null, List.of()));
        AtomicReference<CompletableFuture<Object>> onWorker = new AtomicReference<>();

        inObservation(() -> onWorker.set(asyncProbe.authenticationOnWorker()));

        assertThat(onWorker.get().get(10, TimeUnit.SECONDS)).isNull();
    }

    // ---- helpers -------------------------------------------------------------

    /** Runs {@code body} inside an observation (as a request would) and returns its trace id. */
    private String inObservation(Runnable body) {
        AtomicReference<String> traceId = new AtomicReference<>();
        Observation.createNotStarted("tracing-test", observationRegistry).observe(() -> {
            traceId.set(tracer.currentSpan().context().traceId());
            body.run();
        });
        assertThat(traceId.get()).matches("[0-9a-f]{32}");
        return traceId.get();
    }

    private ILoggingEvent probeLine() {
        assertThat(probeLog.list).as("the probe filter logged inside the request").isNotEmpty();
        return probeLog.list.get(probeLog.list.size() - 1);
    }

    private static WireMockServer startedWireMock() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    /** The same request, sent to WireMock: what DNS would do for a Service name in the cell. */
    private record RedirectedRequest(HttpRequest delegate, int port) implements HttpRequest {
        @Override
        public org.springframework.http.HttpMethod getMethod() {
            return delegate.getMethod();
        }

        @Override
        public URI getURI() {
            return UriComponentsBuilder.fromUri(delegate.getURI()).host("localhost").port(port).build(true).toUri();
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }
}
