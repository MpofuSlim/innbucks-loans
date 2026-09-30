package zw.co.innbucks.loans.security;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.controller.AuthController;
import zw.co.innbucks.loans.controller.CurrentUserController;
import zw.co.innbucks.loans.controller.MerchantController;
import zw.co.innbucks.loans.core.api.LoginRequest;
import zw.co.innbucks.loans.core.api.LoginResponse;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.core.merchant.MerchantService;
import zw.co.innbucks.loans.core.user.CreateUserService;
import zw.co.innbucks.loans.core.user.FindUserService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A session signed in on a temporary password may change it and do nothing else. Runs the REAL
 * {@link ApiSecurityConfig} (both chains, the JWT roles converter, method security) over real controllers; a
 * mocked {@link JwtDecoder} turns each bearer string into a token, and the services behind are mocked.
 */
@SpringJUnitWebConfig(TemporaryPasswordWebTest.Config.class)
class TemporaryPasswordWebTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CHANGE = """
            {"currentPassword": "Temp#Pass42", "newPassword": "Kariba-Sunset-2026"}""";

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, AuthController.class, CurrentUserController.class,
            MerchantController.class})
    static class Config {
        @Bean
        MarketTimeZone marketTimeZone() {
            return new MarketTimeZone("ZW");
        }
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuthService authService;
    @MockitoBean CreateUserService createUserService;
    @MockitoBean FindUserService findUserService;
    @MockitoBean LoanService loanService;
    @MockitoBean MerchantService merchantService;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        givenToken("restricted-agent", UserGroup.AGENTS, true);
        givenToken("restricted-admin", UserGroup.SUPER_ADMIN, true);
        givenToken("fresh-agent", UserGroup.AGENTS, false);
        User agent = new User();
        agent.setId(7L);
        agent.setUsername("tmoyo");
        when(findUserService.resolveUserFromAccessToken(any())).thenReturn(Optional.of(agent));
    }

    /** Bearer "{name}"; {@code temporary} mints it as JwtService does for a temporary password. */
    private void givenToken(String name, UserGroup group, boolean temporary) {
        Jwt.Builder jwt = Jwt.withTokenValue(name).header("alg", "HS256")
                .subject("7f1c2a9e-0000-4000-8000-000000000007")
                .claim("preferred_username", "tmoyo")
                .claim("realm_access", Map.of("roles", List.of(group.name())))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600));
        if (temporary) {
            jwt.claim(JwtService.TEMPORARY_PASSWORD_CLAIM, true);
        }
        when(jwtDecoder.decode(name)).thenReturn(jwt.build());
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("a restricted session calling an ordinary endpoint → 403 PASSWORD_CHANGE_REQUIRED, and nothing runs")
    void ordinaryEndpointIsRefused() throws Exception {
        mvc.perform(as("restricted-agent", get("/lending/v1/me/sales")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Change your temporary password before continuing"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(loanService, findUserService);
    }

    @Test
    @DisplayName("the role does not matter: a restricted SUPER_ADMIN is refused too")
    void evenSuperAdminIsRefused() throws Exception {
        mvc.perform(as("restricted-admin", get("/lending/v1/merchants")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        verify(merchantService, never()).findMerchants(anyBoolean());
    }

    @Test
    @DisplayName("only PUT is the way out: another method on the same path is refused too")
    void onlyThePutIsAllowed() throws Exception {
        mvc.perform(as("restricted-agent", post("/lending/v1/me/password"))
                        .contentType(MediaType.APPLICATION_JSON).content(CHANGE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("PUT /me/password is allowed, and the fresh session it returns is unrestricted")
    void changingThePasswordIsAllowed() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(LoginResponse.builder()
                .accessToken("fresh-agent").tokenType("Bearer").expiresIn(86400).temporaryPassword(false).build());

        mvc.perform(as("restricted-agent", put("/lending/v1/me/password"))
                        .contentType(MediaType.APPLICATION_JSON).content(CHANGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed"))
                .andExpect(jsonPath("$.data.temporaryPassword").value(false))
                .andExpect(jsonPath("$.data.accessToken").value("fresh-agent"));
        verify(authService).resetPassword(eq("Kariba-Sunset-2026"), any(), eq("tmoyo"));

        mvc.perform(as("fresh-agent", get("/lending/v1/me/sales")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    @DisplayName("sign-in, the probes and the API docs are not behind the restriction")
    void publicPathsAreUnaffected() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(LoginResponse.builder()
                .accessToken("restricted-agent").tokenType("Bearer").expiresIn(86400).temporaryPassword(true).build());

        mvc.perform(as("restricted-agent", post("/lending/v1/auth/login")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tmoyo\",\"password\":\"Temp#Pass42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temporaryPassword").value(true));
        // 404, not 403: the chain let them through (this context has no actuator or springdoc).
        for (String path : List.of("/actuator/health", "/actuator/health/readiness", "/v3/api-docs",
                "/swagger-ui/index.html")) {
            mvc.perform(as("restricted-agent", get(path))).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("a token without the claim (a chosen password, or minted before the claim existed) is unrestricted,"
            + " and no token at all is still a 401")
    void unrestrictedAndAnonymous() throws Exception {
        mvc.perform(as("fresh-agent", get("/lending/v1/me/sales"))).andExpect(status().isOk());
        mvc.perform(get("/lending/v1/me/sales"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("the documented 403 is the body the chain writes")
    void documentedBodyIsTheRealOne() throws Exception {
        assertThat(JSON.readTree(ApiExamples.PASSWORD_CHANGE_REQUIRED))
                .isEqualTo(JSON.readTree(SecurityErrorResponses.PASSWORD_CHANGE_REQUIRED_BODY));
        String written = mvc.perform(as("restricted-agent", get("/lending/v1/me/sales")))
                .andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(written)).isEqualTo(JSON.readTree(ApiExamples.PASSWORD_CHANGE_REQUIRED));
    }
}
