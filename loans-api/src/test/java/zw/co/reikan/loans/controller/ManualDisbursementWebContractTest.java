package zw.co.reikan.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.reikan.loans.advice.RestExceptionHandler;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.ManualDisbursementResult;
import zw.co.reikan.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.reikan.loans.core.exception.DisbursementNotAllowedException;
import zw.co.reikan.loans.core.exception.NotFoundException;
import zw.co.reikan.loans.core.loan.InternalApprovalService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/loans/{id}/disburse} moves money, so it is BULKIT_ADMIN-only. The
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

        LoanManagementController controller = new LoanManagementController();
        ReflectionTestUtils.setField(controller, "disbursementService", disbursementService);
        ReflectionTestUtils.setField(controller, "internalApprovalService", mock(InternalApprovalService.class));

        ProxyFactory secured = new ProxyFactory(controller);
        secured.setProxyTargetClass(true);
        secured.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());

        mvc = MockMvcBuilders.standaloneSetup(secured.getProxy())
                .setControllerAdvice(new RestExceptionHandler())
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

        mvc.perform(post("/api/loans/42/disburse"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(disbursementService);
    }

    @Test
    @DisplayName("a CREDIT_MANAGER → 403: approving a loan is not paying it")
    void creditManagerIsForbidden() throws Exception {
        signInAs("CREDIT_MANAGER");

        mvc.perform(post("/api/loans/42/disburse"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disbursementService);
    }

    @Test
    @DisplayName("a BULKIT_ADMIN reaches the service → 200 naming the outcome and the reference")
    void adminReachesTheService() throws Exception {
        signInAs("BULKIT_ADMIN");
        when(disbursementService.disburse(42L)).thenReturn(ManualDisbursementResult.builder()
                .outcome(ManualDisbursementResult.Outcome.DISBURSED)
                .reference("MD-000000042")
                .message("Paid by manual recovery payout MD-000000042 (InnBucks auth A123)")
                .build());

        mvc.perform(post("/api/loans/42/disburse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DISBURSED"))
                .andExpect(jsonPath("$.reference").value("MD-000000042"));
        verify(disbursementService).disburse(42L);
    }

    @Test
    @DisplayName("an in-doubt payout is still a 200 — the body, not the status, tells the operator to confirm")
    void inDoubtIsAnsweredWithItsOutcome() throws Exception {
        signInAs("BULKIT_ADMIN");
        when(disbursementService.disburse(42L)).thenReturn(ManualDisbursementResult.builder()
                .outcome(ManualDisbursementResult.Outcome.IN_DOUBT)
                .reference("MD-000000042")
                .message("Confirm with InnBucks whether MD-000000042 was paid")
                .build());

        mvc.perform(post("/api/loans/42/disburse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IN_DOUBT"));
    }

    @Test
    @DisplayName("an ineligible loan → 409 carrying the reason")
    void ineligibleLoanIsConflict() throws Exception {
        signInAs("BULKIT_ADMIN");
        when(disbursementService.disburse(anyLong()))
                .thenThrow(new DisbursementNotAllowedException("Loan 000000042 is already disbursed (reference MD-000000042)"));

        mvc.perform(post("/api/loans/42/disburse"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Loan 000000042 is already disbursed (reference MD-000000042)"));
    }

    @Test
    @DisplayName("an unknown loan → 404")
    void unknownLoanIsNotFound() throws Exception {
        signInAs("BULKIT_ADMIN");
        when(disbursementService.disburse(7L)).thenThrow(new NotFoundException("Loan 7 not found"));

        mvc.perform(post("/api/loans/7/disburse"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Loan 7 not found"));
    }
}
