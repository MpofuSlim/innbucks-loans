package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.DisbursementService;
import zw.co.innbucks.loans.core.ManualDisbursementResponse;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.exception.DisbursementNotAllowedException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.CreditDecisionService;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /lending/v1/loans/{loanId}/disbursements} moves money, so it is SUPER_ADMIN-only. The
 * controller runs behind the same {@code @PreAuthorize} interceptor production's
 * {@code @EnableMethodSecurity} installs, and callers are authenticated exactly as
 * production does it: a token's {@code realm_access.roles} through
 * {@link RolesJwtAuthenticationConverter}. No database, no Spring context.
 */
class ManualDisbursementWebContractTest {

    private DisbursementService disbursementService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        disbursementService = mock(DisbursementService.class);

        LoanController controller = new LoanController(mock(LoanService.class), mock(LoanReadScopeResolver.class),
                mock(CreditDecisionService.class), disbursementService);

        ProxyFactory secured = new ProxyFactory(controller);
        secured.setProxyTargetClass(true);
        secured.addAdvisor(WorkflowTestSupport.preAuthorize());

        mvc = MockMvcBuilders.standaloneSetup(secured.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("someone")
                .claim("realm_access", Map.of("roles", List.of(role)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new RolesJwtAuthenticationConverter().convert(jwt));
    }

    @Test
    @DisplayName("an AGENT → 403; the payout is never attempted")
    void agentIsForbidden() throws Exception {
        signInAs("AGENTS");

        mvc.perform(post("/lending/v1/loans/42/disbursements"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(disbursementService);
    }

    @Test
    @DisplayName("a CREDIT_MANAGER → 403: approving a loan is not paying it")
    void creditManagerIsForbidden() throws Exception {
        signInAs("CREDIT_MANAGER");

        mvc.perform(post("/lending/v1/loans/42/disbursements"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disbursementService);
    }

    @Test
    @DisplayName("a SUPER_ADMIN reaches the service → 200 naming the outcome and the reference")
    void adminReachesTheService() throws Exception {
        signInAs("SUPER_ADMIN");
        when(disbursementService.disburse(42L)).thenReturn(ManualDisbursementResponse.builder()
                .outcome(ManualDisbursementResponse.Outcome.DISBURSED)
                .reference("MD-000000042")
                .message("Paid by manual recovery payout MD-000000042 (InnBucks auth A123)")
                .build());

        mvc.perform(post("/lending/v1/loans/42/disbursements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.outcome").value("DISBURSED"))
                .andExpect(jsonPath("$.data.reference").value("MD-000000042"));
        verify(disbursementService).disburse(42L);
    }

    @Test
    @DisplayName("an in-doubt payout is still a 200 — the body, not the status, tells the operator to confirm")
    void inDoubtIsAnsweredWithItsOutcome() throws Exception {
        signInAs("SUPER_ADMIN");
        when(disbursementService.disburse(42L)).thenReturn(ManualDisbursementResponse.builder()
                .outcome(ManualDisbursementResponse.Outcome.IN_DOUBT)
                .reference("MD-000000042")
                .message("Confirm with InnBucks whether MD-000000042 was paid")
                .build());

        mvc.perform(post("/lending/v1/loans/42/disbursements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("IN_DOUBT"));
    }

    @Test
    @DisplayName("an ineligible loan → 409 carrying the reason")
    void ineligibleLoanIsConflict() throws Exception {
        signInAs("SUPER_ADMIN");
        when(disbursementService.disburse(anyLong()))
                .thenThrow(new DisbursementNotAllowedException("Loan 000000042 is already disbursed (reference MD-000000042)"));

        mvc.perform(post("/lending/v1/loans/42/disbursements"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISBURSEMENT_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Loan 000000042 is already disbursed (reference MD-000000042)"));
    }

    @Test
    @DisplayName("a loan its payout authorisation has not cleared → 409 naming the checkpoint")
    void unclearedCheckpointIsConflict() throws Exception {
        signInAs("SUPER_ADMIN");
        String waiting = "Loan 000000042 is waiting for Payout authorisation, which was switched on before its booking"
                + " was sent and has not cleared it. A manual payout is not allowed";
        when(disbursementService.disburse(42L)).thenThrow(new DisbursementNotAllowedException(waiting));

        mvc.perform(post("/lending/v1/loans/42/disbursements"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISBURSEMENT_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(waiting));
    }

    @Test
    @DisplayName("a SUPER_ADMIN who approved the loan at Credit → 403 saying why: the payout is a second person's")
    void creditApproverIsForbiddenWithTheReason() throws Exception {
        signInAs("SUPER_ADMIN");
        String why = "Loan 000000042 was approved by admin, who cannot also pay it out; another SUPER_ADMIN must";
        when(disbursementService.disburse(42L)).thenThrow(new AccessDeniedException(why));

        mvc.perform(post("/lending/v1/loans/42/disbursements"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(why));
    }

    @Test
    @DisplayName("an unknown loan → 404")
    void unknownLoanIsNotFound() throws Exception {
        signInAs("SUPER_ADMIN");
        when(disbursementService.disburse(7L)).thenThrow(new NotFoundException("Loan 7 not found"));

        mvc.perform(post("/lending/v1/loans/7/disbursements"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Loan 7 not found"));
    }
}
