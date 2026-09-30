package zw.co.innbucks.loans.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.LoansApiApplication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec's server is what the fleet gateway's Swagger UI offers for Try-it-out, so it must be the one
 * gateway-relative entry every fleet service publishes, and nothing else.
 */
class OpenApiConfigTest {

    private static List<Server> serversFor(String publicApiPrefix) {
        return new OpenApiConfig(publicApiPrefix).openAPI().getServers();
    }

    @Test
    @DisplayName("no PUBLIC_API_PREFIX (the box, local) → the one server is \"/\"")
    void blankPrefixIsTheDomainRoot() {
        for (String blank : new String[]{"", "   ", null}) {
            assertThat(serversFor(blank)).singleElement().satisfies(server -> {
                assertThat(server.getUrl()).isEqualTo("/");
                assertThat(server.getDescription()).isEqualTo("Gateway relative server");
            });
        }
    }

    @Test
    @DisplayName("PUBLIC_API_PREFIX=/foundry (the ZW cell) → the one server is \"/foundry\"")
    void cellPrefixIsTheServer() {
        assertThat(serversFor("/foundry")).singleElement().satisfies(server -> {
            assertThat(server.getUrl()).isEqualTo("/foundry");
            assertThat(server.getDescription()).isEqualTo("Gateway relative server");
        });
    }

    @Test
    @DisplayName("@OpenAPIDefinition names no servers: springdoc applies it after the bean, so any would replace it")
    void theAnnotationNamesNoServers() {
        OpenAPIDefinition definition = LoansApiApplication.class.getAnnotation(OpenAPIDefinition.class);

        assertThat(definition).isNotNull();
        assertThat(definition.servers()).isEmpty();
    }
}
