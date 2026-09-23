package zw.co.reikan.loans.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import zw.co.reikan.loans.advice.RestExceptionHandler;
import zw.co.reikan.loans.core.api.CreateAgentRequest;
import zw.co.reikan.loans.core.api.CreateUserResponse;
import zw.co.reikan.loans.core.api.UserDTO;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.loan.DisbursementType;
import zw.co.reikan.loans.core.loan.LoanBatchRepository;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.loan.LoanService;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.merchant.MerchantMapperImpl;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.merchant.MerchantService;
import zw.co.reikan.loans.core.user.CreateUserService;
import zw.co.reikan.loans.core.user.FindUserService;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserGroup;
import zw.co.reikan.loans.core.user.UserRepository;
import zw.co.reikan.loans.security.ApiSecurityConfig;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may create users, change where a merchant is paid, and wipe test data.
 * Runs the REAL {@link ApiSecurityConfig} (filter chains, JWT roles converter,
 * method security) and the real {@link RestExceptionHandler} over the real
 * controllers; only the persistence/service edges are mocked, and a mocked
 * {@link JwtDecoder} turns each bearer string into a token for one role.
 * No database, no Boot auto-configuration.
 */
@SpringJUnitWebConfig(AdminEndpointsAuthorizationTest.Config.class)
@ActiveProfiles("test-environment")
class AdminEndpointsAuthorizationTest {

    private static final String OWN_MERCHANT = "merchant-a";
    private static final String OTHER_MERCHANT = "merchant-b";

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, RestExceptionHandler.class, MerchantController.class,
            TruncationController.class})
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
    @MockitoBean LoanRepository loanRepository;
    @MockitoBean LoanBatchRepository loanBatchRepository;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        givenToken("admin", UserGroup.BULKIT_ADMIN);
        givenToken("agent", UserGroup.AGENTS);
        givenToken("super", UserGroup.ORGANISATION_SUPER_USER);
        givenToken("consultant", UserGroup.SUB_AGENTS);
        when(createUserService.create(any(CreateAgentRequest.class), any(), any()))
                .thenReturn(new CreateUserResponse(new UserDTO()));
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

    private static String newUser(UserGroup group) {
        return """
                {"username":"new.user","firstName":"New","lastName":"User","mobileNumber":"0772123123",
                 "idNumber":"63-1234567A63","group":"%s"}
                """.formatted(group);
    }

    private static final String PAYEE_REDIRECT = """
            {"companyName":"Innbucks","disbursementType":"MERCHANT_MOBILE_WALLET","accountNumber":"263779990001"}
            """;

    // ── Creating users: POST /api/merchants/{code}/agents and /users ─────────

    @Test
    @DisplayName("AGENTS creating a CREDIT_MANAGER → 403, nothing created")
    void agentCannotCreateCreditManager() throws Exception {
        mvc.perform(as("agent", post("/api/merchants/{code}/agents", OWN_MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.CREDIT_MANAGER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(
                        "Not allowed to create a CREDIT_MANAGER user; your role may create: [SUB_AGENTS]"));
        verifyNoInteractions(createUserService);
    }

    @Test
    @DisplayName("AGENTS creating a BULKIT_ADMIN through /users → 403 too")
    void agentCannotCreateAdminThroughUsersPath() throws Exception {
        mvc.perform(as("agent", post("/api/merchants/{code}/users", OWN_MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.BULKIT_ADMIN)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(createUserService);
    }

    @Test
    @DisplayName("AGENTS creating a SUB_AGENTS user in another merchant → 403")
    void agentCannotCreateInAnotherMerchant() throws Exception {
        mvc.perform(as("agent", post("/api/merchants/{code}/agents", OTHER_MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.SUB_AGENTS)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Not allowed to create users in merchant merchant-b: "
                        + "you may only create users in your own merchant"));
        verifyNoInteractions(createUserService);
    }

    @Test
    @DisplayName("AGENTS creating a SUB_AGENTS user in their own merchant → reaches the service, parented to them")
    void agentCreatesOwnSubAgent() throws Exception {
        mvc.perform(as("agent", post("/api/merchants/{code}/agents", OWN_MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.SUB_AGENTS)))
                .andExpect(status().isOk());

        ArgumentCaptor<User> parent = ArgumentCaptor.forClass(User.class);
        verify(createUserService).create(any(CreateAgentRequest.class), parent.capture(), eq(OWN_MERCHANT));
        assertThat(parent.getValue().getUsername()).isEqualTo("agent");
    }

    @Test
    @DisplayName("ORGANISATION_SUPER_USER granting ORGANISATION_SUPER_USER → 403: admin-only group")
    void superUserCannotGrantSuperUser() throws Exception {
        mvc.perform(as("super", post("/api/merchants/{code}/users", OWN_MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.ORGANISATION_SUPER_USER)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(createUserService);
    }

    @Test
    @DisplayName("SUB_AGENTS may not create users at all → 403 from the role gate, on both paths")
    void subAgentIsRefusedByRoleGate() throws Exception {
        for (String path : List.of("/api/merchants/{code}/agents", "/api/merchants/{code}/users")) {
            mvc.perform(as("consultant", post(path, OWN_MERCHANT))
                            .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.SUB_AGENTS)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("Access Denied"));
        }
        verifyNoInteractions(createUserService, findUserService);
    }

    @Test
    @DisplayName("no token → 401")
    void anonymousCannotCreateUsers() throws Exception {
        mvc.perform(post("/api/merchants/{code}/agents", OWN_MERCHANT)
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.SUB_AGENTS)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(createUserService);
    }

    @Test
    @DisplayName("BULKIT_ADMIN creating a CREDIT_MANAGER in another merchant → reaches the service")
    void adminCreatesCreditManager() throws Exception {
        mvc.perform(as("admin", post("/api/merchants/{code}/agents", OTHER_MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.CREDIT_MANAGER)))
                .andExpect(status().isOk());

        ArgumentCaptor<CreateAgentRequest> request = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(createUserService).create(request.capture(), isNull(), eq(OTHER_MERCHANT));
        assertThat(request.getValue().getGroup()).isEqualTo(UserGroup.CREDIT_MANAGER);
    }

    // ── Merchant payee: POST/PUT /api/merchants, GET /api/merchants ─────────

    @Test
    @DisplayName("AGENTS redirecting the default merchant's payouts → 403, nothing saved or audited")
    void agentCannotUpdateMerchant() throws Exception {
        mvc.perform(as("agent", put("/api/merchants/{code}", Merchant.DEFAULT_MERCHANT_CODE))
                        .contentType(MediaType.APPLICATION_JSON).content(PAYEE_REDIRECT))
                .andExpect(status().isForbidden());
        verify(merchantRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("AGENTS creating a merchant → 403")
    void agentCannotCreateMerchant() throws Exception {
        mvc.perform(as("agent", post("/api/merchants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"evil","companyName":"Evil","disbursementType":"MERCHANT_MOBILE_WALLET",
                                 "accountNumber":"263779990001","commissionStructure":"AGENT_DEFINED"}
                                """))
                .andExpect(status().isForbidden());
        verify(merchantRepository, never()).save(any());
    }

    @Test
    @DisplayName("BULKIT_ADMIN changing the payee → 200 and audited with who, merchant and masked old/new")
    void adminUpdateIsAudited() throws Exception {
        Merchant merchant = Merchant.builder().merchantCode(Merchant.DEFAULT_MERCHANT_CODE).companyName("Innbucks")
                .disbursementType(DisbursementType.CUSTOMER_MOBILE_WALLET).accountNumber("263771112222").build();
        merchant.setId(1L);
        when(merchantRepository.findByMerchantCode(Merchant.DEFAULT_MERCHANT_CODE)).thenReturn(Optional.of(merchant));
        when(merchantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(as("admin", put("/api/merchants/{code}", Merchant.DEFAULT_MERCHANT_CODE))
                        .contentType(MediaType.APPLICATION_JSON).content(PAYEE_REDIRECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("263779990001"));

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
    @DisplayName("GET /api/merchants masks the account for an agent and not for an admin")
    void merchantListMasksForNonAdmins() throws Exception {
        when(merchantRepository.findAll()).thenReturn(List.of(Merchant.builder().merchantCode(OWN_MERCHANT)
                .accountNumber("263771234567").commissionGroup(new CommissionGroup()).build()));

        mvc.perform(as("agent", get("/api/merchants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchants[0].accountNumber").value("****4567"));
        mvc.perform(as("super", get("/api/merchants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchants[0].accountNumber").value("****4567"));
        mvc.perform(as("admin", get("/api/merchants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchants[0].accountNumber").value("263771234567"));
    }

    // ── Test-support fixtures ────────────────────────────────────────────────

    @Test
    @DisplayName("the wipe needs a token: anonymous → 401, nothing deleted")
    void truncateNeedsAuthentication() throws Exception {
        mvc.perform(post("/api/test-support/truncate").param("deleteLoans", "true"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(loanRepository);
    }

    @Test
    @DisplayName("the wipe is BULKIT_ADMIN only: AGENTS → 403, nothing deleted")
    void truncateNeedsAdmin() throws Exception {
        mvc.perform(as("agent", post("/api/test-support/truncate")).param("deleteLoans", "true"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(loanRepository);
    }

    @Test
    @DisplayName("BULKIT_ADMIN POST wipes what was asked for")
    void adminCanTruncate() throws Exception {
        mvc.perform(as("admin", post("/api/test-support/truncate")).param("deleteLoans", "true"))
                .andExpect(status().isOk());
        verify(loanRepository).deleteAll();
        verifyNoInteractions(loanBatchRepository);
    }

    @Test
    @DisplayName("the wipe no longer answers GET → 405, nothing deleted")
    void truncateIsNotAGet() throws Exception {
        mvc.perform(as("admin", get("/api/test-support/truncate")).param("deleteLoans", "true"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(loanRepository);
    }

    @Test
    @DisplayName("the old anonymous /api/auth/... wipe path is gone → 404, nothing deleted")
    void oldAnonymousWipePathIsGone() throws Exception {
        mvc.perform(get("/api/auth/6fab0a61-637b-4cb9-a9ac-ddf61af3202a").param("deleteLoans", "true"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(loanRepository);
    }

    @Test
    @DisplayName("the seed is BULKIT_ADMIN only: AGENTS → 403")
    void seedNeedsAdmin() throws Exception {
        mvc.perform(as("agent", post("/api/test-support/agents"))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.AGENTS)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(createUserService);
    }

    @Test
    @DisplayName("the seed refuses any group but AGENTS/SUB_AGENTS, even for an admin → 400")
    void seedRefusesPrivilegedGroups() throws Exception {
        mvc.perform(as("admin", post("/api/test-support/agents"))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.CREDIT_MANAGER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The test seed only creates [AGENTS, SUB_AGENTS] users"));
        verifyNoInteractions(createUserService);
    }

    @Test
    @DisplayName("BULKIT_ADMIN seeding an AGENTS user → created in the default merchant")
    void adminCanSeedAgent() throws Exception {
        CommissionGroup group = new CommissionGroup();
        group.setId(3L);
        when(commissionGroupRepository.findByNameIgnoreCase(any())).thenReturn(Optional.of(group));

        mvc.perform(as("admin", post("/api/test-support/agents"))
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(UserGroup.AGENTS)))
                .andExpect(status().isOk());
        verify(createUserService).create(any(CreateAgentRequest.class), isNull(), eq(Merchant.DEFAULT_MERCHANT_CODE));
    }
}
