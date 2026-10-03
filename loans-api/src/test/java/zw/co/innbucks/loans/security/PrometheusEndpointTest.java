package zw.co.innbucks.loans.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /actuator/prometheus} over real HTTP, with the packaged {@code application.yml} (exposure, the
 * {@code application} tag, the fleet's latency buckets) and the REAL {@link ApiSecurityConfig}: the cell's
 * Prometheus, presenting the fleet's scrape token, reads latency histograms; nobody else reads anything.
 *
 * <p>The database, JPA and Flyway are switched off, so this needs no Postgres: the endpoint and its security
 * are what is under test, not the application's beans. Readiness's {@code db} member is therefore absent, which
 * is why group-membership validation is off here and only here.
 */
@SpringBootTest(classes = PrometheusEndpointTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "monitoring.scrape-token=" + PrometheusEndpointTest.SCRAPE_TOKEN,
                "management.endpoint.health.validate-group-membership=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
        })
class PrometheusEndpointTest {

    static final String SCRAPE_TOKEN = "test-scrape-token-0123456789abcdef";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ApiSecurityConfig.class, Probe.class})
    static class App {
        /** No loans token is valid here: what matters is that none opens the scrape. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("not in this test");
            };
        }
    }

    /** A secured API path, so the scrape token can be shown NOT to open the API. */
    @RestController
    static class Probe {
        @GetMapping("/lending/v1/probe")
        String probe() {
            return "ok";
        }
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @Test
    @DisplayName("the scraper's token reads http_server_requests_seconds_bucket, tagged loans-service, in the fleet's buckets")
    void scrapeWithTheToken_readsLatencyHistograms() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get("/lending/v1/probe", null).statusCode()).isEqualTo(401);

        HttpResponse<String> scrape = get("/actuator/prometheus", SCRAPE_TOKEN);

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/plain"));
        String body = scrape.body();
        assertThat(body).contains("http_server_requests_seconds_bucket");
        assertThat(body).containsPattern(
                "http_server_requests_seconds_bucket\\{[^}]*application=\"loans-service\"[^}]*uri=\"/actuator/health\"");
        // The fleet's fixed SLO buckets (50ms..5s), not percentiles-histogram's ~70.
        assertThat(body).containsPattern("http_server_requests_seconds_bucket\\{[^}]*le=\"0\\.05\"");
        assertThat(body).containsPattern("http_server_requests_seconds_bucket\\{[^}]*le=\"5\\.0\"");
        assertThat(body).doesNotContainPattern("http_server_requests_seconds_bucket\\{[^}]*le=\"0\\.001\"");
        assertThat(body).contains("jvm_memory_used_bytes", "jvm_gc_");
    }

    @Test
    @DisplayName("no token, a wrong token, or a bearer token: the scrape is a 401")
    void scrapeWithoutTheToken_isUnauthorized() throws Exception {
        assertThat(get("/actuator/prometheus", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/prometheus", "wrong-" + SCRAPE_TOKEN).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/prometheus", SCRAPE_TOKEN.substring(1)).statusCode()).isEqualTo(401);
        HttpResponse<String> bearer = send(HttpRequest.newBuilder(uri("/actuator/prometheus"))
                .header("Authorization", "Bearer some.loans.token").GET().build());
        assertThat(bearer.statusCode()).isEqualTo(401);
        assertThat(bearer.body()).doesNotContain("http_server_requests");
    }

    @Test
    @DisplayName("the scrape token opens nothing else: the API and every other actuator path stay secured")
    void scrapeToken_opensNothingElse() throws Exception {
        for (String path : List.of("/lending/v1/probe", "/actuator", "/actuator/metrics", "/actuator/env",
                "/actuator/beans", "/actuator/prometheus/extra")) {
            assertThat(get(path, SCRAPE_TOKEN).statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("the probes stay public")
    void healthStaysPublic() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            assertThat(get(path, null).statusCode()).as(path).isEqualTo(200);
        }
    }

    private HttpResponse<String> get(String path, String scrapeToken) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (scrapeToken != null) {
            request.header(MetricsScrapeAuthFilter.TOKEN_HEADER, scrapeToken);
        }
        return send(request.build());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
