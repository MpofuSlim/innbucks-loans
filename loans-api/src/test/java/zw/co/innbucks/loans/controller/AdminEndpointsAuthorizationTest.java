package zw.co.innbucks.loans.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import zw.co.innbucks.loans.core.api.CreateUserRequest;
import zw.co.innbucks.loans.core.api.LoginRequest;
import zw.co.innbucks.loans.core.api.LoginResponse;
import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionGroupRepository;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantMapperImpl;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.merchant.MerchantService;
import zw.co.innbucks.loans.core.user.CreateUserService;
import zw.co.innbucks.loans.core.user.FindUserService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may create and list users, and change where a merchant is paid.
 * Runs the REAL {@link ApiSecurityConfig} (filter chains, JWT roles converter,
 * method security) and the real {@link GlobalExceptionHandler} over the real
 * controllers; only the persistence/service edges are mocked, and a mocked
 * {@link JwtDecoder} turns each bearer string into a token for one role.
 * No database, no Boot auto-configuration.
 */
@SpringJUnitWebConfig(AdminEndpointsAuthorizationTest.Config.class)
class AdminEndpointsAuthorizationTest {

    private static final String OWN_MERCHANT = "merchant-a";
    private static final String OTHER_MERCHANT = "merchant-b";

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, MerchantController.class, AuthController.class})
    static class Config {
        /** Real service over mocked repositories, so masking and auditing run as in production. */
        @Bean
        MerchantService merchantService(MerchantRepository merchantRepository,
                                        CommissionGroupRepository commissionGroupRepository,
                                        AuditService auditService) {
            return new MerchantService(merchantRepository, new MerchantMapperImpl(), commissionGroupRepository,
                    auditService);
        }
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean MerchantRepository merchantRepository;
    @MockitoBean CommissionGroupRepository commissionGroupRepository;
    @MockitoBean AuditService auditService;
    @MockitoBean AuthService authService;
    @MockitoBean CreateUserService createUserService;
    @MockitoBean LoanService loanService;
    @MockitoBean FindUserService findUserService;
    @MockitoBean UserRepository userRepository;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        givenToken("admin", UserGroup.SUPER_ADMIN);
        givenToken("agent", UserGroup.AGENTS);
        givenToken("credit", UserGroup.CREDIT_MANAGER);
        givenToken("finance", UserGroup.FINANCE);
        when(createUserService.create(any(CreateUserRequest.class), any()))
                .thenReturn(UserResponse.builder().username("new.user").build());
    }

    /** Bearer "{username}-token" authenticates as {username} holding exactly {group}. */
    private void givenToken(String username, UserGroup group) {
        Jwt jwt = Jwt.withTokenValue(username + "-token").header("alg", "HS256")
                .subject(username + "-sub")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(group.name())))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(jwtDecoder.decode(username + "-token")).thenReturn(jwt);

        Merchant merchant = Merchant.builder().merchantCode(OWN_MERCHANT).build();
        User user = new User();
        user.setUsername(username);
        user.setMerchant(merchant);
        when(findUserService.resolveUserFromAccessToken(argThat(t -> t != null
                && username.equals(t.getClaimAsString("preferred_username"))))).thenReturn(Optional.of(user));
    }

    private static MockHttpServletRequestBuilder as(String username, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + username + "-token");
    }

    private static String newUser(Object group) {
        return """
                {"username":"new.user","firstName":"New","lastName":"User","mobileNumber":"0772123123",
                 "idNumber":"63-1234567A63","group":"%s"}
                """.formatted(group);
    }

    private static final String PAYEE_REDIRECT = """
            {"companyName":"Innbucks","disbursementType":"MERCHANT_MOBILE_WALLET","accountNumber":"263779990001"}
            """;

    // ── The security chain's own answers ──────────────────────────────────

    @Test
    @DisplayName("no token → 401 in the standard envelope")
    void anonymousIs401InTheEnvelope() throws Exception {
        mvc.perform(get("/lending/v1/merchants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Invalid or missing token"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("a token the decoder refuses (expired, forged, revoked) → the same 401")
    void badTokenIs401InTheEnvelope() throws Exception {
        when(jwtDecoder.decode("stale-token")).thenThrow(new BadJwtException("expired"));

        mvc.perform(get("/lending/v1/merchants").header("Authorization", "Bearer stale-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Invalid or missing token"));
    }

    @Test
    @DisplayName("sign-in needs no token")
    void signInIsPublic() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(LoginResponse.builder()
                .accessToken("issued").tokenType("Bearer").expiresIn(3600).build());

        mvc.perform(post("/lending/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"agent\",\"password\":\"Secret#123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.accessToken").value("issued"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }

    @Test
    @DisplayName("forgot-password answers the same whether or not the username exists")
    void forgotPasswordNeverSaysWhetherTheAccountExists() throws Exception {
        mvc.perform(post("/lending/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(AuthController.FORGOT_PASSWORD_MESSAGE));
    }

    @Test
    @DisplayName("the old /api paths are gone: behind the secured chain, an anonymous call is a 401")
    void oldPathsAreNotPublic() throws Exception {
        mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ── Creating users: POST /lending/v1/merchants/{merchantCode}/users ───────

    @Test
    @DisplayName("only SUPER_ADMIN creates users: AGENTS, CREDIT_MANAGER and FINANCE → 403")
    void onlyAdminCreatesUsers() throws Exception {
        for (String caller : List.of("agent", "credit", "finance")) {
            mvc.perform(as(caller, post("/lending/v1/merchants/{code}/users", OWN_MERCHANT))
                            .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.AGENTS)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("Forbidden - insufficient role"));
        }
        verifyNoInteractions(createUserService, findUserService);
    }

    @Test
    @DisplayName("no token → 401")
    void anonymousCannotCreateUsers() throws Exception {
        mvc.perform(post("/lending/v1/merchants/{code}/users", OWN_MERCHANT)
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.AGENTS)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(createUserService);
    }

    @Test
    @DisplayName("SUPER_ADMIN creating a CREDIT_MANAGER in another merchant → reaches the service")
    void adminCreatesCreditManager() throws Exception {
        mvc.perform(as("admin", post("/lending/v1/merchants/{code}/users", OTHER_MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.CREDIT_MANAGER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.data.username").value("new.user"));

        ArgumentCaptor<CreateUserRequest> request = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(createUserService).create(request.capture(), eq(OTHER_MERCHANT));
        assertThat(request.getValue().getGroup()).isEqualTo(UserGroup.CREDIT_MANAGER);
    }

    @Test
    @DisplayName("a retired group in the body (SUB_AGENTS) is a 400, even for an admin: it no longer exists")
    void retiredGroupIsRefused() throws Exception {
        mvc.perform(as("admin", post("/lending/v1/merchants/{code}/users", OWN_MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser("SUB_AGENTS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(createUserService);
    }

    // ── Listing a merchant's users: GET /lending/v1/merchants/{merchantCode}/users ─

    @Test
    @DisplayName("listing a merchant's users is SUPER_ADMIN and CREDIT_MANAGER only: AGENTS and FINANCE → 403")
    void listingUsersIsStaffOnly() throws Exception {
        when(authService.findUsersByMerchantCode(OWN_MERCHANT))
                .thenReturn(List.of(UserResponse.builder().username("agent").build()));

        for (String caller : List.of("agent", "finance")) {
            mvc.perform(as(caller, get("/lending/v1/merchants/{code}/users", OWN_MERCHANT)))
                    .andExpect(status().isForbidden());
        }
        for (String caller : List.of("admin", "credit")) {
            mvc.perform(as(caller, get("/lending/v1/merchants/{code}/users", OWN_MERCHANT)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].password").doesNotExist());
        }
    }

    // ── Merchant payee: POST/PUT/GET /lending/v1/merchants ──────────────────

    @Test
    @DisplayName("AGENTS redirecting the default merchant's payouts → 403, nothing saved or audited")
    void agentCannotUpdateMerchant() throws Exception {
        mvc.perform(as("agent", put("/lending/v1/merchants/{code}", Merchant.DEFAULT_MERCHANT_CODE))
                        .contentType(MediaType.APPLICATION_JSON).content(PAYEE_REDIRECT))
                .andExpect(status().isForbidden());
        verify(merchantRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("AGENTS creating a merchant → 403")
    void agentCannotCreateMerchant() throws Exception {
        mvc.perform(as("agent", post("/lending/v1/merchants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantCode":"evil","companyName":"Evil","disbursementType":"MERCHANT_MOBILE_WALLET",
                                 "accountNumber":"263779990001","commissionStructure":"AGENT_DEFINED"}
                                """))
                .andExpect(status().isForbidden());
        verify(merchantRepository, never()).save(any());
    }

    @Test
    @DisplayName("SUPER_ADMIN changing the payee → 200 and audited with who, merchant and masked old/new")
    void adminUpdateIsAudited() throws Exception {
        Merchant merchant = Merchant.builder().merchantCode(Merchant.DEFAULT_MERCHANT_CODE).companyName("Innbucks")
                .disbursementType(DisbursementType.CUSTOMER_MOBILE_WALLET).accountNumber("263771112222").build();
        merchant.setId(1L);
        when(merchantRepository.findByMerchantCode(Merchant.DEFAULT_MERCHANT_CODE)).thenReturn(Optional.of(merchant));
        when(merchantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(as("admin", put("/lending/v1/merchants/{code}", Merchant.DEFAULT_MERCHANT_CODE))
                        .contentType(MediaType.APPLICATION_JSON).content(PAYEE_REDIRECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountNumber").value("263779990001"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(captor.capture());
        AuditLog audit = captor.getValue().build();
        assertThat(audit.getEventType()).isEqualTo(MerchantService.PAYEE_CHANGED_EVENT);
        assertThat(audit.getActorId()).isEqualTo("admin-sub");
        assertThat(audit.getDetail()).isEqualTo("merchantCode=" + Merchant.DEFAULT_MERCHANT_CODE
                + " disbursementType CUSTOMER_MOBILE_WALLET -> MERCHANT_MOBILE_WALLET,"
                + " accountNumber ****2222 -> ****0001");
    }

    @Test
    @DisplayName("GET /lending/v1/merchants masks the account for an agent and not for an admin")
    void merchantListMasksForNonAdmins() throws Exception {
        CommissionGroup group = CommissionGroup.builder().name("80-20-Favouring-InnBucks").percentage(true).build();
        when(merchantRepository.findAll(any(Sort.class))).thenReturn(List.of(Merchant.builder()
                .merchantCode(OWN_MERCHANT).accountNumber("263771234567").commissionGroup(group).build()));

        mvc.perform(as("agent", get("/lending/v1/merchants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].accountNumber").value("****4567"))
                // The group as the API shows it, not the entity.
                .andExpect(jsonPath("$.data[0].commissionGroup.name").value("80-20-Favouring-InnBucks"))
                .andExpect(jsonPath("$.data[0].commissionGroup.enabled").doesNotExist());
        mvc.perform(as("credit", get("/lending/v1/merchants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].accountNumber").value("****4567"));
        mvc.perform(as("admin", get("/lending/v1/merchants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].accountNumber").value("263771234567"));
    }
}
