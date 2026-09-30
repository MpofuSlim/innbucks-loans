package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.loan.DeductionCancellationResponse;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.DeductionCancellationStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
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

        DeductionCancellationController management = new DeductionCancellationController(cancellationService);
        ProxyFactory proxy = new ProxyFactory(management);
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(WorkflowTestSupport.preAuthorize());


        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(proxy.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler())
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

    private static DeductionCancellationResponse required() {
        return DeductionCancellationResponse.builder()
                .loanId(42L).reference("000000042").ecNumber("*****67A")
                .grossedMonthlyDeduction(new BigDecimal("98.50")).batchNumber("BATCH-20260901-07")
                .reason("BOOKING_IN_DOUBT").requestedAt(LocalDateTime.of(2026, 9, 20, 8, 30))
                .action(DeductionCancellationService.operatorAction("BOOKING_IN_DOUBT"))
                .disbursementStatusMessage("InnBucks loan application failed: I/O error: Read timed out")
                .status(DeductionCancellationStatus.REQUIRED)
                .build();
    }

    @Test
    @DisplayName("GET /lending/v1/deduction-cancellations as FINANCE → 200 with the REQUIRED loans")
    void listAsFinance() throws Exception {
        signedInAs("FINANCE");
        when(cancellationService.findRequired()).thenReturn(List.of(required()));

        mvc.perform(get("/lending/v1/deduction-cancellations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].loanId").value(42))
                .andExpect(jsonPath("$.data[0].reference").value("000000042"))
                .andExpect(jsonPath("$.data[0].ecNumber").value("*****67A"))
                .andExpect(jsonPath("$.data[0].grossedMonthlyDeduction").value(98.50))
                .andExpect(jsonPath("$.data[0].batchNumber").value("BATCH-20260901-07"))
                .andExpect(jsonPath("$.data[0].reason").value("BOOKING_IN_DOUBT"))
                .andExpect(jsonPath("$.data[0].action").value(containsString(
                        "confirm with InnBucks that no loan was booked")))
                .andExpect(jsonPath("$.data[0].disbursementStatusMessage")
                        .value("InnBucks loan application failed: I/O error: Read timed out"))
                .andExpect(jsonPath("$.data[0].status").value("REQUIRED"))
                .andExpect(jsonPath("$.data[0].requestedAt").exists())
                .andExpect(jsonPath("$.data[0].note").doesNotExist());
    }

    @Test
    @DisplayName("GET /lending/v1/deduction-cancellations as CREDIT_MANAGER or SUPER_ADMIN → 200")
    void listAsCreditManagerAndAdmin() throws Exception {
        when(cancellationService.findRequired()).thenReturn(List.of());

        signedInAs("CREDIT_MANAGER");
        mvc.perform(get("/lending/v1/deduction-cancellations")).andExpect(status().isOk());
        signedInAs("SUPER_ADMIN");
        mvc.perform(get("/lending/v1/deduction-cancellations")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /lending/v1/deduction-cancellations as AGENTS → 403, and the service is never asked")
    void listAsAgentIsForbidden() throws Exception {
        signedInAs("AGENTS");

        mvc.perform(get("/lending/v1/deduction-cancellations"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(cancellationService);
    }

    @Test
    @DisplayName("GET /lending/v1/deduction-cancellations with no authentication → 401")
    void listUnauthenticatedIsUnauthorized() throws Exception {
        mvc.perform(get("/lending/v1/deduction-cancellations"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(cancellationService);
    }

    @Test
    @DisplayName("PUT /lending/v1/loans/{loanId}/deduction-cancellation as FINANCE → 200 with who and when")
    void recordAsFinance() throws Exception {
        signedInAs("FINANCE");
        when(cancellationService.markCancelledExternally(42L, "Cancelled on Ndasenda portal, ref NDC-551"))
                .thenReturn(DeductionCancellationResponse.builder()
                        .loanId(42L).reference("000000042").status(DeductionCancellationStatus.CANCELLED_EXTERNALLY)
                        .note("Cancelled on Ndasenda portal, ref NDC-551").cancelledBy("finance.officer")
                        .cancelledAt(LocalDateTime.of(2026, 9, 23, 11, 0))
                        .build());

        mvc.perform(put("/lending/v1/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED_EXTERNALLY"))
                .andExpect(jsonPath("$.data.cancelledBy").value("finance.officer"))
                .andExpect(jsonPath("$.data.cancelledAt").exists());
    }

    @Test
    @DisplayName("PUT /lending/v1/loans/{loanId}/deduction-cancellation as SUPER_ADMIN → 200")
    void recordAsAdmin() throws Exception {
        signedInAs("SUPER_ADMIN");
        when(cancellationService.markCancelledExternally(eq(42L), any())).thenReturn(required());

        mvc.perform(put("/lending/v1/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /lending/v1/loans/{loanId}/deduction-cancellation as AGENTS or CREDIT_MANAGER → 403, nothing recorded")
    void recordAsAgentOrCreditManagerIsForbidden() throws Exception {
        signedInAs("AGENTS");
        mvc.perform(put("/lending/v1/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isForbidden());
        signedInAs("CREDIT_MANAGER");
        mvc.perform(put("/lending/v1/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isForbidden());

        verifyNoInteractions(cancellationService);
    }

    @Test
    @DisplayName("PUT /lending/v1/loans/{loanId}/deduction-cancellation on a loan with none pending → 409 with the reason")
    void recordWhenNotRequiredIsConflict() throws Exception {
        signedInAs("FINANCE");
        when(cancellationService.markCancelledExternally(eq(42L), any()))
                .thenThrow(new ConflictException("Loan 42 has no deduction cancellation pending"));

        mvc.perform(put("/lending/v1/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Loan 42 has no deduction cancellation pending"));
    }

    @Test
    @DisplayName("PUT /lending/v1/loans/{loanId}/deduction-cancellation with no note → 400, nothing recorded")
    void recordWithoutNoteIsBadRequest() throws Exception {
        signedInAs("FINANCE");

        mvc.perform(put("/lending/v1/loans/42/deduction-cancellation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.note").value("A note on how the deduction was cancelled is required"));
        verify(cancellationService, never()).markCancelledExternally(anyLong(), any());
    }
}
