package zw.co.innbucks.loans.config;

import com.github.benmanes.caffeine.cache.Policy;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.config.AsyncExecutorConfig;
import zw.co.innbucks.loans.core.config.RejectedTasks;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packaged {@code application.yml} with Boot's real auto-configuration: every cache bounded, expiring and
 * measured; the {@code @Async} executor bounded and dropping-and-counting when full; and no Swagger UI, while the spec
 * the fleet gateway aggregates ({@code /v3/api-docs}) is still served without a token. The database, JPA and Flyway
 * are off, as in {@code PrometheusEndpointTest}.
 */
@SpringBootTest(classes = RuntimeBoundsTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoint.health.validate-group-membership=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
        })
class RuntimeBoundsTest {

    static final List<String> CACHES = List.of("parameterCache", "innbucks-access-token-cache",
            "ndasenda-access-token-cache");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableCaching
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, AsyncExecutorConfig.class, Probe.class})
    static class App {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("not in this test");
            };
        }
    }

    /** One documented endpoint, so the spec has something in it. */
    @RestController
    static class Probe {
        @GetMapping("/lending/v1/probe")
        String probe() {
            return "ok";
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    CacheManager cacheManager;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    ThreadPoolTaskExecutor applicationTaskExecutor;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    // ---- caches --------------------------------------------------------------

    @Test
    @DisplayName("exactly the three caches, each Caffeine with a size bound, a five-minute TTL and statistics on")
    void everyCacheIsBoundedAndExpires() {
        assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
        assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrderElementsOf(CACHES);

        for (String name : CACHES) {
            com.github.benmanes.caffeine.cache.Cache<Object, Object> cache =
                    ((CaffeineCache) cacheManager.getCache(name)).getNativeCache();
            Policy<Object, Object> policy = cache.policy();
            assertThat(policy.eviction()).as(name + " bound").hasValueSatisfying(
                    eviction -> assertThat(eviction.getMaximum()).isEqualTo(500));
            assertThat(policy.expireAfterWrite()).as(name + " TTL").hasValueSatisfying(
                    expiry -> assertThat(expiry.getExpiresAfter()).isEqualTo(Duration.ofMinutes(5)));
            assertThat(policy.isRecordingStats()).as(name + " stats").isTrue();
        }
    }

    @Test
    @DisplayName("a cache name the code does not list is refused, never created as a cache with no bound")
    void noDynamicCaches() {
        assertThat(cacheManager.getCache("not-configured")).isNull();
    }

    @Test
    @DisplayName("every cache is on the metrics (cache.gets, cache.size), so a hit rate or a full cache shows")
    void cachesAreMeasured() {
        for (String name : CACHES) {
            assertThat(meterRegistry.find("cache.gets").tag("cache", name).functionCounters()).as(name).isNotEmpty();
            assertThat(meterRegistry.find("cache.size").tag("cache", name).gauge()).as(name).isNotNull();
        }
    }

    // ---- @Async --------------------------------------------------------------

    @Test
    @DisplayName("the @Async executor is bounded (8..16 threads, 2000 queued) and drops and counts when full")
    void asyncExecutorIsBounded() {
        assertThat(applicationTaskExecutor.getCorePoolSize()).isEqualTo(8);
        assertThat(applicationTaskExecutor.getMaxPoolSize()).isEqualTo(16);
        assertThat(applicationTaskExecutor.getQueueCapacity()).isEqualTo(2000);
        assertThat(applicationTaskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(2000);
        assertThat(applicationTaskExecutor.getThreadPoolExecutor().getRejectedExecutionHandler().getClass().getName())
                .isEqualTo(AsyncExecutorConfig.class.getName() + "$DropAndCount");
        assertThat(meterRegistry.get(RejectedTasks.METRIC).tag("executor", "applicationTaskExecutor").counter().count())
                .as("registered at 0").isZero();
    }

    // ---- Swagger -------------------------------------------------------------

    @Test
    @DisplayName("the spec the gateway aggregates is still served at /v3/api-docs, without a token")
    void apiDocsStillServed() throws Exception {
        HttpResponse<String> docs = get("/v3/api-docs");

        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("\"openapi\"", "/lending/v1/probe");
    }

    @Test
    @DisplayName("the bundled Swagger UI is not served: its pages are 404s")
    void swaggerUiIsOff() throws Exception {
        for (String path : List.of("/swagger-ui.html", "/swagger-ui/index.html", "/swagger-ui/swagger-initializer.js",
                "/v3/api-docs/swagger-config")) {
            assertThat(get(path).statusCode()).as(path).isEqualTo(404);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
