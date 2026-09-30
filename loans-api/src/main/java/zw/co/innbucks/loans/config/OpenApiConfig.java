package zw.co.innbucks.loans.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The spec's one server, the same as every fleet service's: the gateway, addressed relative to the page
 * the Swagger UI was loaded from. The fleet gateway's aggregated UI lists this API as loans-service and
 * shows this entry as "/foundry - Gateway relative server" in the ZW cell, so Try-it-out calls
 * /foundry/lending/v1/..., which the edge and the gateway route here.
 *
 * <p>Title, description and the bearer scheme stay on {@code @OpenAPIDefinition} in
 * {@code LoansApiApplication}, which must name no servers: springdoc applies that annotation after this
 * bean, and servers named there would replace this one.</p>
 */
@Configuration
public class OpenApiConfig {

    static final String GATEWAY_SERVER_DESCRIPTION = "Gateway relative server";

    /**
     * Path prefix the public edge mounts the fleet under (/foundry in the ZW cell). Swagger UI resolves
     * the server URL in the BROWSER against the public origin, so it must carry the prefix even though
     * nginx strips it before the request reaches the gateway. Blank (the default, and the box) falls back
     * to "/": the domain root.
     */
    private final String publicApiPrefix;

    public OpenApiConfig(@Value("${PUBLIC_API_PREFIX:}") String publicApiPrefix) {
        this.publicApiPrefix = publicApiPrefix;
    }

    @Bean
    public OpenAPI openAPI() {
        Server server = new Server();
        server.setUrl(publicApiPrefix == null || publicApiPrefix.isBlank() ? "/" : publicApiPrefix.strip());
        server.setDescription(GATEWAY_SERVER_DESCRIPTION);
        return new OpenAPI().servers(List.of(server));
    }
}
