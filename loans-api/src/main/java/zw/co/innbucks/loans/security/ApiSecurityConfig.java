package zw.co.innbucks.loans.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.web.ApiPaths;

/**
 * No CORS here, deliberately. Browsers reach this API through the fleet api-gateway, and CORS lives
 * there alone (its globalcors): the gateway ADDS a backend's response headers to its own, so a CORS
 * policy here as well would send a second Access-Control-Allow-Origin, and a browser refuses a response
 * that carries two. Leaving out {@code .cors()} is not enough on its own: Spring Security turns CORS on
 * for every chain by itself whenever a {@code UrlBasedCorsConfigurationSource} bean exists, so none may
 * be declared (GatewaySurfaceTest).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class ApiSecurityConfig {

    private static final String AUTH_PATHS = ApiPaths.BASE + "/auth/**";

    private static final String[] UNSECURED_PATHS = {
            AUTH_PATHS,
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/spec.html",
            "/swagger-resources/**",
            "/configuration/security",
            // The cell's probes. Only health is exposed (application.yml), and it shows no details.
            // Named exactly, never /actuator/**: any other actuator path stays behind a token.
            "/actuator/health",
            "/actuator/health/**"
    };

    @Bean
    protected SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new RegisterSessionAuthenticationStrategy(new SessionRegistryImpl());
    }

    @Bean
    @Order(1)
    SecurityFilterChain unsecuredSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(UNSECURED_PATHS)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers(AUTH_PATHS))
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
                .build();
    }

    /**
     * Default chain for everything not matched by {@link #unsecuredSecurityFilterChain}.
     * It deliberately has NO {@code securityMatcher} so it is a catch-all: any route
     * that is not an explicitly public path requires a valid token. This closes the
     * gap where root-mapped controllers fell outside a path-prefix matcher and were reachable
     * with no authentication. A missing, expired or revoked token is a 401 and a role refusal a
     * 403, both in the standard error envelope rather than an empty body.
     */
    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new RolesJwtAuthenticationConverter()))
                        .authenticationEntryPoint(SecurityErrorResponses::unauthorized)
                        .accessDeniedHandler(SecurityErrorResponses::forbidden))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(SecurityErrorResponses::unauthorized)
                        .accessDeniedHandler(SecurityErrorResponses::forbidden))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }
}
