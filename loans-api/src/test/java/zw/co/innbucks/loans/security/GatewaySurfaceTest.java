package zw.co.innbucks.loans.security;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import zw.co.innbucks.loans.controller.AuthController;
import zw.co.innbucks.loans.core.api.LoginRequest;
import zw.co.innbucks.loans.core.api.LoginResponse;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.user.CreateUserService;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What running behind the fleet api-gateway asks of the security chain: the cell's probes and the
 * gateway's spec fetch get through without a token, nothing else under /actuator does, and this API
 * sends no CORS headers of its own (the gateway owns CORS, and two Access-Control-Allow-Origin headers
 * fail in the browser). Runs the REAL {@link ApiSecurityConfig} over a plain MVC context: no actuator
 * and no springdoc are loaded, so a path the chain lets through answers 404 while a path it guards
 * answers 401.
 */
@SpringJUnitWebConfig(GatewaySurfaceTest.Config.class)
class GatewaySurfaceTest {

    private static final String BROWSER_ORIGIN = "https://portal.example.com";

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, AuthController.class})
    static class Config {
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuthService authService;
    @MockitoBean CreateUserService createUserService;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    // ── Probes and the spec: through the chain with no token ──────────────

    @Test
    @DisplayName("the probes' paths need no token (404 here only because this context has no actuator)")
    void healthPathsArePublic() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("the gateway's spec fetch needs no token (404 here only because this context has no springdoc)")
    void apiDocsArePublic() throws Exception {
        for (String path : List.of("/v3/api-docs", "/v3/api-docs/swagger-config")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("nothing else under /actuator is public: an anonymous call is a 401")
    void otherActuatorPathsNeedAToken() throws Exception {
        for (String path : List.of("/actuator", "/actuator/env", "/actuator/beans", "/actuator/healthdump")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    // ── CORS: the gateway's alone ─────────────────────────────────────────

    @Test
    @DisplayName("no UrlBasedCorsConfigurationSource bean: the one kind Spring Security turns CORS on for by itself")
    void noCorsConfigurationSource() {
        assertThat(context.getBeanNamesForType(UrlBasedCorsConfigurationSource.class)).isEmpty();
    }

    @Test
    @DisplayName("a cross-origin sign-in is answered with no Access-Control-Allow-Origin")
    void publicResponseCarriesNoCorsHeaders() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(LoginResponse.builder()
                .accessToken("issued").tokenType("Bearer").expiresIn(3600).build());

        mvc.perform(post("/lending/v1/auth/login").header(HttpHeaders.ORIGIN, BROWSER_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"agent\",\"password\":\"Secret#123\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    @DisplayName("a cross-origin call to a secured path is a plain 401, with no Access-Control-Allow-Origin")
    void securedResponseCarriesNoCorsHeaders() throws Exception {
        mvc.perform(get("/lending/v1/loans").header(HttpHeaders.ORIGIN, BROWSER_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("a preflight that reaches this service is not answered with CORS headers either")
    void preflightCarriesNoCorsHeaders() throws Exception {
        mvc.perform(options("/lending/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, BROWSER_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
    }
}
