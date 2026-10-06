package zw.co.innbucks.loans.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SWAGGER_UI_ENABLED=true} (docker-compose.yml's dev stack) brings the bundled Swagger UI back; the packaged
 * default, off, is {@link RuntimeBoundsTest}'s.
 */
@SpringBootTest(classes = RuntimeBoundsTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "SWAGGER_UI_ENABLED=true",
                "management.endpoint.health.validate-group-membership=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
        })
class SwaggerUiLocalDevTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("SWAGGER_UI_ENABLED=true serves the UI again, next to the spec")
    void uiCanBeTurnedOnForLocalDevelopment() throws Exception {
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        for (String path : new String[]{"/swagger-ui/index.html", "/v3/api-docs"}) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
        }
    }
}
