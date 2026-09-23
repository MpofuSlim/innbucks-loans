package zw.co.reikan.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import zw.co.reikan.loans.advice.RestExceptionHandler;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.exception.ConflictException;
import zw.co.reikan.loans.core.loan.DeductionCancellationDto;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.DeductionCancellationStatus;
import zw.co.reikan.loans.core.loan.InternalApprovalService;
import zw.co.reikan.loans.core.loan.LoanService;
import zw.co.reikan.loans.core.user.FindUserService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operators' deduction-cancellation queue: who may read it, who may close an item, and the
 * exact statuses the portal codes against. The controller is wrapped in the same
 * {@code @PreAuthorize} interceptor {@code @EnableMethodSecurity} installs, so the role rules are
 * the real ones; no database, no Spring context, no filter chain.
 */
class DeductionCancellationWebContractTest {

    private static final String NOTE = "{\"note\":\"Cancelled on Ndasenda portal, ref NDC-551\"}";

    private DeductionCancellationService cancellationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        cancellationService = mock(DeductionCancellationService.class);

        LoanManagementController management = new LoanManagementController();
        ReflectionTestUtils.setField(management, "deductionCancellationService", cancellationService);
        ReflectionTestUtils.setField(management, "internalApprovalService", mock(InternalApprovalService.class));
        ReflectionTestUtils.setField(management, "disbursementService", mock(DisbursementService.class));
        ProxyFactory proxy = new ProxyFactory(management);
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());

        // Registered alongside, to prove the literal /loans/deduction-cancellations beats /loans/{id}.
        BatchesController batches = new BatchesController();
        ReflectionTestUtils.setField(batches, "loanService", mock(LoanService.class));
        ReflectionTestUtils.setField(batches, "findUserService", mock(FindUserService.class));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(proxy.getProxy(), batches)
                .setControllerAdvice(new RestExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("someone", "n/a", "ROLE_" + role));
    }

    private static DeductionCancellationDto required() {
        return DeductionCancellationDto.builder()
                .id(42L).reference("000000042").ecNumber("*****67A")
                .instalmentLodged(new BigDecimal("98.50")).batchNumber("BATCH-20260901-07")
                .reason("CREDIT_REJECTED").requestedAt(LocalDateTime.of(2026, 9, 20, 8, 30))
                .status(DeductionCancellationStatus.REQUIRED)
                .build();
    }

    @Test
    @DisplayName("GET /api/loans/deduction-cancellations as FINANCE → 200 with the REQUIRED loans")
    void listAsFinance() throws Exception {
        signedInAs("FINANCE");
        when(cancellationService.findRequired()).thenReturn(List.of(required()));

        mvc.perform(get("/api/loans/deduction-cancellations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].reference").value("000000042"))
                .andExpect(jsonPath("$[0].ecNumber").value("*****67A"))
                .andExpect(jsonPath("$[0].instalmentLodged").value(98.50))
                .andExpect(jsonPath("$[0].batchNumber").value("BATCH-20260901-07"))
                .andExpect(jsonPath("$[0].reason").value("CREDIT_REJECTED"))
                .andExpect(jsonPath("$[0].status").value("REQUIRED"))
                .andExpect(jsonPath("$[0].requestedAt").exists())
                .andExpect(jsonPath("$[0].note").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/loans/deduction-cancellations as CREDIT_MANAGER or BULKIT_ADMIN → 200")
    void listAsCreditManagerAndAdmin() throws Exception {
        when(cancellationService.findRequired()).thenReturn(List.of());

        signedInAs("CREDIT_MANAGER");
        mvc.perform(get("/api/loans/deduction-cancellations")).andExpect(status().isOk());
        signedInAs("BULKIT_ADMIN");
        mvc.perform(get("/api/loans/deduction-cancellations")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/loans/deduction-cancellations as AGENTS → 403, and the service is never asked")
    void listAsAgentIsForbidden() throws Exception {
        signedInAs("AGENTS");

        mvc.perform(get("/api/loans/deduction-cancellations"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(cancellationService);
    }

    @Test
    @DisplayName("GET /api/loans/deduction-cancellations with no authentication → 401")
    void listUnauthenticatedIsUnauthorized() throws Exception {
        mvc.perform(get("/api/loans/deduction-cancellations"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(cancellationService);
    }

    @Test
    @DisplayName("PUT /api/loans/{id}/deduction-cancellation as FINANCE → 200 with who and when")
    void recordAsFinance() throws Exception {
        signedInAs("FINANCE");
        when(cancellationService.markCancelledExternally(42L, "Cancelled on Ndasenda portal, ref NDC-551"))
                .thenReturn(DeductionCancellationDto.builder()
                        .id(42L).reference("000000042").status(DeductionCancellationStatus.CANCELLED_EXTERNALLY)
                        .note("Cancelled on Ndasenda portal, ref NDC-551").cancelledBy("finance.officer")
                        .cancelledAt(LocalDateTime.of(2026, 9, 23, 11, 0))
                        .build());

        mvc.perform(put("/api/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED_EXTERNALLY"))
                .andExpect(jsonPath("$.cancelledBy").value("finance.officer"))
                .andExpect(jsonPath("$.cancelledAt").exists());
    }

    @Test
    @DisplayName("PUT /api/loans/{id}/deduction-cancellation as BULKIT_ADMIN → 200")
    void recordAsAdmin() throws Exception {
        signedInAs("BULKIT_ADMIN");
        when(cancellationService.markCancelledExternally(eq(42L), any())).thenReturn(required());

        mvc.perform(put("/api/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /api/loans/{id}/deduction-cancellation as AGENTS or CREDIT_MANAGER → 403, nothing recorded")
    void recordAsAgentOrCreditManagerIsForbidden() throws Exception {
        signedInAs("AGENTS");
        mvc.perform(put("/api/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isForbidden());
        signedInAs("CREDIT_MANAGER");
        mvc.perform(put("/api/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isForbidden());

        verifyNoInteractions(cancellationService);
    }

    @Test
    @DisplayName("PUT /api/loans/{id}/deduction-cancellation on a loan with none pending → 409 with the reason")
    void recordWhenNotRequiredIsConflict() throws Exception {
        signedInAs("FINANCE");
        when(cancellationService.markCancelledExternally(eq(42L), any()))
                .thenThrow(new ConflictException("Loan 42 has no deduction cancellation pending"));

        mvc.perform(put("/api/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Loan 42 has no deduction cancellation pending"));
    }

    @Test
    @DisplayName("PUT /api/loans/{id}/deduction-cancellation with no note → 400, nothing recorded")
    void recordWithoutNoteIsBadRequest() throws Exception {
        signedInAs("FINANCE");

        mvc.perform(put("/api/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("note: A note on how the deduction was cancelled is required"));
        verify(cancellationService, never()).markCancelledExternally(anyLong(), any());
    }
}
