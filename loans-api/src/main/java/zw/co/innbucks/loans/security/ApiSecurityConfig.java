package zw.co.innbucks.loans.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.web.ApiPaths;

import java.util.List;

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
            "/configuration/security"
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
                .cors(Customizer.withDefaults())
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
                .cors(Customizer.withDefaults())
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

    /**
     * Single source of truth for CORS. Consumed by {@code .cors(withDefaults())} on
     * both filter chains, so preflight {@code OPTIONS} requests are handled inside
     * the security chain — before authorization — and every response (including the
     * secured endpoints) carries the CORS headers. This replaces the
     * previous standalone CorsFilter + SimpleCORSFilter, which ran after Spring
     * Security and so never got to answer a preflight on an authenticated route.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
