package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.loan.LoanResponse;
import zw.co.innbucks.loans.core.loan.PayslipFraudFlagResponse;
import zw.co.innbucks.loans.core.loan.PayslipFraudReason;
import zw.co.innbucks.loans.core.loan.PayslipReviewRequest;
import zw.co.innbucks.loans.core.loan.PayslipReviewResponse;
import zw.co.innbucks.loans.core.loan.PayslipReviewService;
import zw.co.innbucks.loans.core.loan.PayslipReviewStatus;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payslip review queue and decision (FR-SSB-007) are Credit's: an agent can neither see the queue nor
 * decide it. Runs behind production's {@code @PreAuthorize} interceptor; no database, no Spring context.
 */
class PayslipReviewWebContractTest {

    private PayslipReviewService payslipReviewService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        payslipReviewService = mock(PayslipReviewService.class);
        ProxyFactory secured = new ProxyFactory(new PayslipReviewController(payslipReviewService));
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
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
                .claim("preferred_username", "someone")
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new RolesJwtAuthenticationConverter().convert(jwt));
    }

    @Test
    @DisplayName("an agent can neither see the queue nor decide it (403); the service is never called")
    void agentIsForbidden() throws Exception {
        signInAs("AGENTS");

        mvc.perform(get("/lending/v1/payslip-reviews"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/loans/57/payslip-review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"CLEARED\",\"comment\":\"fine\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(payslipReviewService);
    }

    @Test
    @DisplayName("a credit manager reads the queue with each finding")
    void creditManagerReadsTheQueue() throws Exception {
        signInAs("CREDIT_MANAGER");
        when(payslipReviewService.queue()).thenReturn(List.of(new PayslipReviewResponse(57L, "000000057", null,
                "tmoyo", "harare-motors", "Tendai", "Ncube", "7654321B", "637654321B42", null, null,
                List.of(new PayslipFraudFlagResponse(PayslipFraudReason.PAYSLIP_REUSED_BY_ANOTHER_APPLICANT,
                        "Same payslip file as loan 000000042", 42L, "000000042", "Rudo", "Chikwanha", "1234567A",
                        null, null, null, null, null)))));

        mvc.perform(get("/lending/v1/payslip-reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reference").value("000000057"))
                .andExpect(jsonPath("$.data[0].flags[0].reason").value("PAYSLIP_REUSED_BY_ANOTHER_APPLICANT"))
                .andExpect(jsonPath("$.data[0].flags[0].matchedReference").value("000000042"))
                .andExpect(jsonPath("$.data[0].flags[0].matchedNationalIdNumber").doesNotExist());
    }

    @Test
    @DisplayName("a decision needs an outcome and a comment, reported together")
    void outcomeAndCommentAreRequired() throws Exception {
        signInAs("CREDIT_MANAGER");

        mvc.perform(post("/lending/v1/loans/57/payslip-review").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.outcome").value("Outcome is required (CLEARED or CONFIRMED)"))
                .andExpect(jsonPath("$.data.comment").value("Comment is required"));
        verifyNoInteractions(payslipReviewService);
    }

    @Test
    @DisplayName("CONFIRMED answers with the rejected loan and says so")
    void confirmAnswersWithTheLoan() throws Exception {
        signInAs("CREDIT_MANAGER");
        LoanResponse loan = new LoanResponse();
        loan.setId(57L);
        loan.setPayslipReviewStatus(PayslipReviewStatus.CONFIRMED);
        when(payslipReviewService.review(eq(57L), any())).thenReturn(loan);

        mvc.perform(post("/lending/v1/loans/57/payslip-review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"CONFIRMED\",\"comment\":\"Payslip belongs to loan 42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Suspected fraud confirmed; the application is rejected"))
                .andExpect(jsonPath("$.data.payslipReviewStatus").value("CONFIRMED"));
        verify(payslipReviewService).review(57L,
                new PayslipReviewRequest(PayslipReviewStatus.CONFIRMED, "Payslip belongs to loan 42"));
    }

    @Test
    @DisplayName("a loan not waiting for review is a 409")
    void notPendingIs409() throws Exception {
        signInAs("SUPER_ADMIN");
        when(payslipReviewService.review(eq(57L), any()))
                .thenThrow(new ConflictException("Loan 000000057 has no payslip review pending"));

        mvc.perform(post("/lending/v1/loans/57/payslip-review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"CLEARED\",\"comment\":\"fine\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Loan 000000057 has no payslip review pending"));
    }
}
