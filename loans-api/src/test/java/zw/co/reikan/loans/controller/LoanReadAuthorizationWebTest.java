package zw.co.reikan.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.reikan.loans.advice.RestExceptionHandler;
import zw.co.reikan.loans.core.api.FindLoansRequest;
import zw.co.reikan.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.reikan.loans.core.exception.NotFoundException;
import zw.co.reikan.loans.core.loan.LoanBatchService;
import zw.co.reikan.loans.core.loan.LoanDto;
import zw.co.reikan.loans.core.loan.LoanReadScope;
import zw.co.reikan.loans.core.loan.LoanReadScopeResolver;
import zw.co.reikan.loans.core.loan.LoanService;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.ndasenda.NdasendaDeductionsBatchRequest;
import zw.co.reikan.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl;
import zw.co.reikan.loans.core.user.FindUserServiceImpl;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read loans and SSB batches through {@link BatchesController}. The
 * controller is wrapped in the same {@code @PreAuthorize} interceptor that
 * {@code @EnableMethodSecurity} installs, and the caller's roles are read off a
 * real token by the real {@link FindUserServiceImpl} + {@link LoanReadScopeResolver};
 * only the data services are mocked. No database, no Spring context.
 */
class LoanReadAuthorizationWebTest {

    private LoanService loanService;
    private LoanBatchService loanBatchService;
    private NdasendaLoanApprovalServiceImpl ndasenda;
    private UserRepository userRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        loanService = mock(LoanService.class);
        loanBatchService = mock(LoanBatchService.class);
        ndasenda = mock(NdasendaLoanApprovalServiceImpl.class);
        userRepository = mock(UserRepository.class);
        FindUserServiceImpl findUserService = new FindUserServiceImpl(userRepository);

        BatchesController controller = new BatchesController();
        ReflectionTestUtils.setField(controller, "loanService", loanService);
        ReflectionTestUtils.setField(controller, "findUserService", findUserService);
        ReflectionTestUtils.setField(controller, "loanReadScopeResolver", new LoanReadScopeResolver(findUserService));
        ReflectionTestUtils.setField(controller, "loanBatchService", loanBatchService);
        ReflectionTestUtils.setField(controller, "ndasendaLoanApprovalService", ndasenda);

        ProxyFactory secured = new ProxyFactory(controller);
        secured.setProxyTargetClass(true);
        secured.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());

        mvc = MockMvcBuilders.standaloneSetup(secured.getProxy())
                .setControllerAdvice(new RestExceptionHandler())
                .build();

        givenUser("agent.jane", 7L, "M-001");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- /api/loans/search ---------------------------------------------------

    @Test
    @DisplayName("an agent's loan search is scoped to their merchant and the loans they originated")
    void agentSearchReturnsOnlyTheirLoans() throws Exception {
        when(loanService.findLoans(any(FindLoansRequest.class), eq(LoanReadScope.originator("M-001", 7L))))
                .thenReturn(List.of(loan(101L)));

        mvc.perform(post("/api/loans/search").with(as("agent.jane", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loans.length()").value(1))
                .andExpect(jsonPath("$.loans[0].id").value(101));

        verify(loanService, never()).findLoans(any(FindLoansRequest.class));
        verify(loanService, never()).findLoans(any(FindLoansRequest.class), eq(LoanReadScope.platform()));
    }

    @Test
    @DisplayName("a CREDIT_MANAGER's loan search is platform-wide")
    void creditManagerSearchSeesAll() throws Exception {
        when(loanService.findLoans(any(FindLoansRequest.class), eq(LoanReadScope.platform())))
                .thenReturn(List.of(loan(101L), loan(202L)));

        mvc.perform(post("/api/loans/search").with(as("credit.mary", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loans.length()").value(2));
    }

    // --- /api/loans/{id} -----------------------------------------------------

    @Test
    @DisplayName("an agent GETting another merchant's loan → 404, the same answer as a missing id")
    void agentGetOfAnotherMerchantsLoanIs404() throws Exception {
        // The loan exists — the unscoped read would find it — but the scoped read does not.
        when(loanService.getLoan(42L)).thenReturn(loan(42L));
        when(loanService.getLoan(42L, LoanReadScope.originator("M-001", 7L)))
                .thenThrow(new NotFoundException("Loan 42 not found"));

        mvc.perform(get("/api/loans/42").with(as("agent.jane", "AGENTS")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Loan 42 not found"));

        verify(loanService, never()).getLoan(42L);
    }

    @Test
    @DisplayName("a CREDIT_MANAGER can GET any loan")
    void creditManagerGetsAnyLoan() throws Exception {
        when(loanService.getLoan(42L, LoanReadScope.platform())).thenReturn(loan(42L));

        mvc.perform(get("/api/loans/42").with(as("credit.mary", "CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    @DisplayName("a real fault on GET /api/loans/{id} is a 500, no longer disguised as a 404")
    void loanReadFaultIsNotA404() throws Exception {
        when(loanService.getLoan(42L, LoanReadScope.platform()))
                .thenThrow(new IllegalStateException("connection refused"));

        mvc.perform(get("/api/loans/42").with(as("credit.mary", "CREDIT_MANAGER")))
                .andExpect(status().isInternalServerError());
    }

    // --- /api/loans/find-approvals --------------------------------------------

    @Test
    @DisplayName("the credit queue is closed to agents → 403")
    void agentCannotReadCreditQueue() throws Exception {
        mvc.perform(get("/api/loans/find-approvals").with(as("agent.jane", "AGENTS")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(loanService);
    }

    @Test
    @DisplayName("the credit queue is open to a CREDIT_MANAGER")
    void creditManagerReadsCreditQueue() throws Exception {
        when(loanService.findLoans(any(FindLoansRequest.class))).thenReturn(List.of(loan(101L)));

        mvc.perform(get("/api/loans/find-approvals").with(as("credit.mary", "CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loans.length()").value(1));
    }

    // --- /api/batches/** -------------------------------------------------------

    @Test
    @DisplayName("an agent searching SSB batches → 403, Ndasenda never called")
    void agentCannotSearchBatches() throws Exception {
        mvc.perform(post("/api/batches/search").with(as("agent.jane", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ndasenda);
    }

    @Test
    @DisplayName("a sub-agent reading a batch → 403, Ndasenda never called")
    void subAgentCannotReadBatch() throws Exception {
        mvc.perform(get("/api/batches/B-1").with(as("sub.tom", "SUB_AGENTS")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ndasenda, loanBatchService);
    }

    @Test
    @DisplayName("no authentication on a batch read → 401")
    void anonymousBatchReadIs401() throws Exception {
        mvc.perform(get("/api/batches/B-1"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(ndasenda, loanBatchService);
    }

    @Test
    @DisplayName("FINANCE reading a batch this system never submitted → 404, Ndasenda never asked")
    void foreignBatchIs404() throws Exception {
        when(loanBatchService.existsByBatchNumber("FOREIGN-9")).thenReturn(false);

        mvc.perform(get("/api/batches/FOREIGN-9").with(as("fin.sue", "FINANCE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Batch FOREIGN-9 not found"));
        verifyNoInteractions(ndasenda);
    }

    @Test
    @DisplayName("FINANCE reading one of our batches → 200 with the batch")
    void ourBatchIsReturned() throws Exception {
        when(loanBatchService.existsByBatchNumber("B-1")).thenReturn(true);
        when(ndasenda.findDeductionResponsesByBatchId("B-1"))
                .thenReturn(List.of(NdasendaDeductionsBatchRequest.builder().id("B-1").build()));

        mvc.perform(get("/api/batches/B-1").with(as("fin.sue", "FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("B-1"));
    }

    @Test
    @DisplayName("a CREDIT_MANAGER may search batches")
    void creditManagerSearchesBatches() throws Exception {
        when(ndasenda.findBatches(any())).thenReturn(List.of());

        mvc.perform(post("/api/batches/search").with(as("credit.mary", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    // --- helpers ---------------------------------------------------------------

    private void givenUser(String username, Long id, String merchantCode) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setMerchant(Merchant.builder().merchantCode(merchantCode).build());
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
    }

    /**
     * Authenticates the request the way the resource server does: a token carrying
     * {@code realm_access.roles}, converted by the production converter. Set on the
     * security context (read by {@code @PreAuthorize}) and as the request principal
     * (the controller's {@code Principal} argument).
     */
    private static RequestPostProcessor as(String username, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        JwtAuthenticationToken authentication =
                (JwtAuthenticationToken) new RolesJwtAuthenticationConverter().convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return request -> {
            request.setUserPrincipal(authentication);
            return request;
        };
    }

    private static LoanDto loan(Long id) {
        LoanDto dto = new LoanDto();
        dto.setId(id);
        return dto;
    }
}
