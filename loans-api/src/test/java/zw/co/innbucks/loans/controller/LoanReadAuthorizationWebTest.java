package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.DisbursementService;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.CreditDecisionService;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanBatchService;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.loan.LoanResponse;
import zw.co.innbucks.loans.core.loan.LoanSearchCriteria;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.core.loan.LoanSummaryResponse;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.ndasenda.NdasendaDeduction;
import zw.co.innbucks.loans.core.ndasenda.NdasendaDeductionBatch;
import zw.co.innbucks.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl;
import zw.co.innbucks.loans.core.user.FindUserServiceImpl;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read loans and SSB batches, through {@link LoanController} and
 * {@link DeductionBatchController}. The controllers are wrapped in the same {@code @PreAuthorize}
 * interceptor that {@code @EnableMethodSecurity} installs, and the caller's roles are read off a
 * real token by the real {@link FindUserServiceImpl} + {@link LoanReadScopeResolver}; only the data
 * services are mocked. No database, no Spring context.
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

        LoanController loans = new LoanController(loanService, new LoanReadScopeResolver(findUserService),
                mock(CreditDecisionService.class), mock(DisbursementService.class));
        DeductionBatchController batches = new DeductionBatchController(ndasenda, loanBatchService);

        mvc = MockMvcBuilders.standaloneSetup(secured(loans), secured(batches))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        givenUser("agent.jane", 7L, "M-001");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- GET /lending/v1/loans ---------------------------------------------------------------

    @Test
    @DisplayName("an agent's loan list is scoped to their merchant and the loans they originated")
    void agentListReturnsOnlyTheirLoans() throws Exception {
        when(loanService.findLoans(any(), eq(LoanReadScope.originator("M-001", 7L)), any()))
                .thenReturn(new PageImpl<>(List.of(loan(101L)), PageRequest.of(0, 20), 1));

        mvc.perform(get("/lending/v1/loans").with(as("agent.jane", "AGENTS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(101))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));

        verify(loanService, never()).findLoans(any(), eq(LoanReadScope.platform()), any());
    }

    @Test
    @DisplayName("a CREDIT_MANAGER's loan list is platform-wide")
    void creditManagerListSeesAll() throws Exception {
        when(loanService.findLoans(any(), eq(LoanReadScope.platform()), any()))
                .thenReturn(new PageImpl<>(List.of(loan(101L), loan(202L))));

        mvc.perform(get("/lending/v1/loans").with(as("credit.mary", "CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    @DisplayName("filters and paging reach the service; an oversized page is brought down to 100")
    void filtersAndPagingReachTheService() throws Exception {
        when(loanService.findLoans(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/lending/v1/loans").with(as("credit.mary", "CREDIT_MANAGER"))
                        .param("ssbApprovalStatus", "APPROVED")
                        .param("creditApprovalStatus", "PENDING")
                        .param("merchantCode", "M-002")
                        .param("fromDate", "2026-09-01")
                        .param("toDate", "2026-09-30")
                        .param("page", "3")
                        .param("size", "500"))
                .andExpect(status().isOk());

        ArgumentCaptor<LoanSearchCriteria> criteria = ArgumentCaptor.forClass(LoanSearchCriteria.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(loanService).findLoans(criteria.capture(), eq(LoanReadScope.platform()), pageable.capture());
        assertThat(criteria.getValue().ssbApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(criteria.getValue().creditApprovalStatus()).isEqualTo(InternalApprovalStatus.PENDING);
        assertThat(criteria.getValue().merchantCode()).isEqualTo("M-002");
        assertThat(criteria.getValue().fromDate()).hasToString("2026-09-01");
        assertThat(criteria.getValue().toDate()).hasToString("2026-09-30");
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("a filter value that is not one of the statuses is a 400 naming the parameter")
    void unknownStatusIs400() throws Exception {
        mvc.perform(get("/lending/v1/loans").with(as("credit.mary", "CREDIT_MANAGER"))
                        .param("ssbApprovalStatus", "APPROVD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Invalid value for 'ssbApprovalStatus'"));
        verifyNoInteractions(loanService);
    }

    // --- GET /lending/v1/loans/{loanId} -----------------------------------------------------

    @Test
    @DisplayName("an agent GETting another merchant's loan → 404, the same answer as a missing id")
    void agentGetOfAnotherMerchantsLoanIs404() throws Exception {
        when(loanService.getLoan(42L, LoanReadScope.originator("M-001", 7L)))
                .thenThrow(new NotFoundException("Loan 42 not found"));

        mvc.perform(get("/lending/v1/loans/42").with(as("agent.jane", "AGENTS")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Loan 42 not found"));

        verify(loanService, never()).getLoan(42L, LoanReadScope.platform());
    }

    @Test
    @DisplayName("a CREDIT_MANAGER can GET any loan")
    void creditManagerGetsAnyLoan() throws Exception {
        LoanResponse loan = new LoanResponse();
        loan.setId(42L);
        loan.setSsbApprovalStatus(LoanApprovalStatus.APPROVED);
        when(loanService.getLoan(42L, LoanReadScope.platform())).thenReturn(loan);

        mvc.perform(get("/lending/v1/loans/42").with(as("credit.mary", "CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.ssbApprovalStatus").value("APPROVED"));
    }

    @Test
    @DisplayName("a real fault reading a loan is a 500 that says nothing about the fault")
    void loanReadFaultIsAGeneric500() throws Exception {
        when(loanService.getLoan(42L, LoanReadScope.platform()))
                .thenThrow(new IllegalStateException("connection refused to 10.0.0.5"));

        mvc.perform(get("/lending/v1/loans/42").with(as("credit.mary", "CREDIT_MANAGER")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    @DisplayName("a non-numeric loan id is a 400, not a 500")
    void nonNumericLoanIdIs400() throws Exception {
        mvc.perform(get("/lending/v1/loans/abc").with(as("credit.mary", "CREDIT_MANAGER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // --- GET /lending/v1/loans/pending-credit-decision ------------------------------------

    @Test
    @DisplayName("the credit queue is closed to agents → 403")
    void agentCannotReadCreditQueue() throws Exception {
        mvc.perform(get("/lending/v1/loans/pending-credit-decision").with(as("agent.jane", "AGENTS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Forbidden - insufficient role"));
        verifyNoInteractions(loanService);
    }

    @Test
    @DisplayName("the credit queue is open to a CREDIT_MANAGER: SSB-approved, Credit pending, every merchant")
    void creditManagerReadsCreditQueue() throws Exception {
        when(loanService.findLoans(any(), any(), any())).thenReturn(new PageImpl<>(List.of(loan(101L))));

        mvc.perform(get("/lending/v1/loans/pending-credit-decision").with(as("credit.mary", "CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));

        verify(loanService).findLoans(eq(LoanSearchCriteria.awaitingCreditDecision()), eq(LoanReadScope.platform()),
                any());
    }

    // --- /lending/v1/deduction-batches ------------------------------------------------------

    @Test
    @DisplayName("an agent listing SSB batches → 403, Ndasenda never called")
    void agentCannotListBatches() throws Exception {
        mvc.perform(get("/lending/v1/deduction-batches").with(as("agent.jane", "AGENTS")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ndasenda);
    }

    @Test
    @DisplayName("an agent reading a batch → 403, Ndasenda never called")
    void agentCannotReadBatch() throws Exception {
        mvc.perform(get("/lending/v1/deduction-batches/B-1").with(as("agent.tom", "AGENTS")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ndasenda, loanBatchService);
    }

    @Test
    @DisplayName("no authentication on a batch read → 401")
    void anonymousBatchReadIs401() throws Exception {
        mvc.perform(get("/lending/v1/deduction-batches/B-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(ndasenda, loanBatchService);
    }

    @Test
    @DisplayName("FINANCE reading a batch this system never submitted → 404, Ndasenda never asked")
    void foreignBatchIs404() throws Exception {
        when(loanBatchService.existsByBatchNumber("FOREIGN-9")).thenReturn(false);

        mvc.perform(get("/lending/v1/deduction-batches/FOREIGN-9").with(as("fin.sue", "FINANCE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Batch FOREIGN-9 not found"));
        verifyNoInteractions(ndasenda);
    }

    @Test
    @DisplayName("FINANCE reading one of our batches gets it in dollars, with no security token or national ID")
    void ourBatchIsReturnedWithoutSecrets() throws Exception {
        when(loanBatchService.existsByBatchNumber("B-1")).thenReturn(true);
        when(ndasenda.findDeductionResponsesByBatchId("B-1")).thenReturn(List.of(NdasendaDeductionBatch.builder()
                .id("B-1")
                .securityToken("s3cret-token")
                .totalAmountInCents(20896)
                .deductions(List.of(NdasendaDeduction.builder()
                        .reference("000000042").ecNumber("1234567A").idNumber("63-1234567-A-42")
                        .amountInCents(20896).build()))
                .build()));

        String body = mvc.perform(get("/lending/v1/deduction-batches/B-1").with(as("fin.sue", "FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("B-1"))
                .andExpect(jsonPath("$.data.totalAmount").value(208.96))
                .andExpect(jsonPath("$.data.deductions[0].reference").value("000000042"))
                .andExpect(jsonPath("$.data.deductions[0].ecNumber").value("*****67A"))
                .andExpect(jsonPath("$.data.deductions[0].amount").value(208.96))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("s3cret-token", "63-1234567-A-42", "securityToken", "idNumber");
    }

    @Test
    @DisplayName("our batch that SSB has not answered yet → 404 saying so")
    void unansweredBatchIs404() throws Exception {
        when(loanBatchService.existsByBatchNumber("B-2")).thenReturn(true);
        when(ndasenda.findDeductionResponsesByBatchId("B-2")).thenReturn(List.of());

        mvc.perform(get("/lending/v1/deduction-batches/B-2").with(as("fin.sue", "FINANCE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("SSB has not answered batch B-2 yet"));
    }

    @Test
    @DisplayName("a CREDIT_MANAGER may list batches, without their deductions")
    void creditManagerListsBatches() throws Exception {
        when(ndasenda.findBatches(any())).thenReturn(List.of(NdasendaDeductionBatch.builder()
                .id("B-1").deductions(List.of(NdasendaDeduction.builder().reference("000000042").build())).build()));

        mvc.perform(get("/lending/v1/deduction-batches").with(as("credit.mary", "CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("B-1"))
                .andExpect(jsonPath("$.data[0].deductions").doesNotExist());
    }

    // --- helpers -----------------------------------------------------------------------------

    private static Object secured(Object controller) {
        ProxyFactory secured = new ProxyFactory(controller);
        secured.setProxyTargetClass(true);
        secured.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return secured.getProxy();
    }

    private void givenUser(String username, Long id, String merchantCode) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setMerchant(Merchant.builder().merchantCode(merchantCode).build());
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
    }

    /**
     * Authenticates the request the way the resource server does: a token carrying
     * {@code realm_access.roles}, converted by the production converter. Set on the security
     * context (read by {@code @PreAuthorize}) and as the request principal (the controller's
     * {@code JwtAuthenticationToken} argument).
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

    private static LoanSummaryResponse loan(Long id) {
        LoanSummaryResponse summary = new LoanSummaryResponse();
        summary.setId(id);
        return summary;
    }
}
