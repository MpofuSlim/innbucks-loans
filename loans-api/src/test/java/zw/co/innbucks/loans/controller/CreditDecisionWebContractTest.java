package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.DisbursementService;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.CreditDecisionRequest;
import zw.co.innbucks.loans.core.loan.CreditDecisionResponse;
import zw.co.innbucks.loans.core.loan.CreditDecisionService;
import zw.co.innbucks.loans.core.loan.CreditReasonCodeResponse;
import zw.co.innbucks.loans.core.loan.CreditReferralRequest;
import zw.co.innbucks.loans.core.loan.CreditResubmissionRequest;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.loan.LoanResponse;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The credit decision, resubmission, decision log and reason code endpoints: who may call each, the
 * request each validates, and the shapes they answer with. The controllers run behind the same
 * {@code @PreAuthorize} interceptor production installs; no database, no Spring context.
 */
class CreditDecisionWebContractTest {

    private static final LoanReadScope AGENT_SCOPE = LoanReadScope.originator("harare-motors", 7L);

    private CreditDecisionService creditDecisionService;
    private LoanReadScopeResolver loanReadScopeResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        creditDecisionService = mock(CreditDecisionService.class);
        loanReadScopeResolver = mock(LoanReadScopeResolver.class);
        when(loanReadScopeResolver.resolve(any())).thenReturn(AGENT_SCOPE);
        LoanController loans = new LoanController(mock(LoanService.class), loanReadScopeResolver,
                creditDecisionService, mock(DisbursementService.class));
        CreditReasonCodeController reasonCodes = new CreditReasonCodeController(creditDecisionService);

        mvc = MockMvcBuilders.standaloneSetup(secured(loans), secured(reasonCodes))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Object secured(Object controller) {
        ProxyFactory secured = new ProxyFactory(controller);
        secured.setProxyTargetClass(true);
        secured.addAdvisor(WorkflowTestSupport.preAuthorize());
        return secured.getProxy();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /** On the security context for {@code @PreAuthorize}, and as the principal for the controller's argument. */
    private static RequestPostProcessor as(String username, String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
        JwtAuthenticationToken authentication =
                (JwtAuthenticationToken) new RolesJwtAuthenticationConverter().convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return request -> {
            request.setUserPrincipal(authentication);
            return request;
        };
    }

    // ── POST /loans/{loanId}/credit-decision ─────────────────────────────────

    @Test
    @DisplayName("an agent cannot decide a loan (403); the service is never called")
    void agentCannotDecide() throws Exception {
        mvc.perform(post("/lending/v1/loans/42/credit-decision").with(as("tmoyo", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVED","reasonCode":"APPROVE_WITHIN_POLICY","comment":"Fine"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(creditDecisionService);
    }

    @Test
    @DisplayName("a decision without a reason code or a comment is refused, naming both")
    void reasonCodeAndCommentAreRequired() throws Exception {
        mvc.perform(post("/lending/v1/loans/42/credit-decision").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVED","comment":"  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.reasonCode").value("Reason code is required"))
                .andExpect(jsonPath("$.data.comment").value("Comment is required"));
        verifyNoInteractions(creditDecisionService);
    }

    @Test
    @DisplayName("RETURNED is accepted and answered as a return")
    void returnIsAccepted() throws Exception {
        LoanResponse loan = new LoanResponse();
        loan.setId(42L);
        loan.setCreditApprovalStatus(InternalApprovalStatus.RETURNED);
        loan.setCreditDecisionReasonCode("RETURN_PAYSLIP");
        when(creditDecisionService.decide(eq(42L), any())).thenReturn(loan);

        mvc.perform(post("/lending/v1/loans/42/credit-decision").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"RETURNED","reasonCode":"RETURN_PAYSLIP","comment":"Send the August payslip"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Loan returned for more information"))
                .andExpect(jsonPath("$.data.creditApprovalStatus").value("RETURNED"))
                .andExpect(jsonPath("$.data.creditDecisionReasonCode").value("RETURN_PAYSLIP"));
        verify(creditDecisionService).decide(42L, new CreditDecisionRequest(InternalApprovalStatus.RETURNED,
                "RETURN_PAYSLIP", "Send the August payslip"));
    }

    // ── POST /loans/{loanId}/credit-resubmission ─────────────────────────────

    @Test
    @DisplayName("an agent resubmits within their own read scope")
    void agentResubmits() throws Exception {
        LoanResponse loan = new LoanResponse();
        loan.setCreditApprovalStatus(InternalApprovalStatus.PENDING);
        when(creditDecisionService.resubmit(eq(42L), any(), eq(AGENT_SCOPE))).thenReturn(loan);

        mvc.perform(post("/lending/v1/loans/42/credit-resubmission").with(as("tmoyo", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"comment":"August payslip figures confirmed with the bursar"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Loan resubmitted to Credit"))
                .andExpect(jsonPath("$.data.creditApprovalStatus").value("PENDING"));
        verify(creditDecisionService).resubmit(42L,
                new CreditResubmissionRequest("August payslip figures confirmed with the bursar"), AGENT_SCOPE);
    }

    @Test
    @DisplayName("a resubmission needs a comment")
    void resubmissionNeedsAComment() throws Exception {
        mvc.perform(post("/lending/v1/loans/42/credit-resubmission").with(as("tmoyo", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.comment").value("Comment is required"));
        verifyNoInteractions(creditDecisionService);
    }

    // ── GET /loans/{loanId}/credit-decisions ─────────────────────────────────

    @Test
    @DisplayName("an agent cannot read the decision log (403)")
    void agentCannotReadTheLog() throws Exception {
        mvc.perform(get("/lending/v1/loans/42/credit-decisions").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(creditDecisionService);
    }

    @Test
    @DisplayName("the decision log carries each snapshot as JSON, not as an escaped string")
    void logCarriesTheSnapshotAsJson() throws Exception {
        when(creditDecisionService.history(42L)).thenReturn(List.of(new CreditDecisionResponse(17L,
                CreditAction.RETURNED, "RETURN_PAYSLIP", "Payslip missing, unclear or out of date", "Send August",
                "cmanager", null, null, null, "{\"reference\":\"000000042\",\"tenor\":3}", "abc123")));

        mvc.perform(get("/lending/v1/loans/42/credit-decisions").with(as("cmanager", "CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("RETURNED"))
                .andExpect(jsonPath("$.data[0].reasonDescription").value("Payslip missing, unclear or out of date"))
                .andExpect(jsonPath("$.data[0].loanSnapshot.reference").value("000000042"))
                .andExpect(jsonPath("$.data[0].loanSnapshot.tenor").value(3))
                .andExpect(jsonPath("$.data[0].snapshotSha256").value("abc123"))
                .andExpect(jsonPath("$.data[0].performedAt").doesNotExist());
    }

    // ── GET /credit-reason-codes ─────────────────────────────────────────────

    @Test
    @DisplayName("any signed-in user can read the reason codes, for one decision")
    void anyoneReadsTheReasonCodes() throws Exception {
        when(creditDecisionService.reasonCodes(InternalApprovalStatus.RETURNED)).thenReturn(List.of(
                new CreditReasonCodeResponse("RETURN_PAYSLIP", InternalApprovalStatus.RETURNED,
                        "Payslip missing, unclear or out of date")));

        mvc.perform(get("/lending/v1/credit-reason-codes").param("decision", "RETURNED").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("RETURN_PAYSLIP"))
                .andExpect(jsonPath("$.data[0].decision").value("RETURNED"))
                .andExpect(jsonPath("$.data[0].description").value("Payslip missing, unclear or out of date"));
    }

    @Test
    @DisplayName("a decision that is not one is a 400")
    void unknownDecisionIsRefused() throws Exception {
        mvc.perform(get("/lending/v1/credit-reason-codes").param("decision", "MAYBE").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Invalid value for 'decision'"));
        verifyNoInteractions(creditDecisionService);
    }

    // ── POST /loans/{loanId}/credit-referral ─────────────────────────────────

    private static final String REFERRAL = """
            {"recommendation":"APPROVED","reasonCode":"APPROVE_WITHIN_POLICY","comment":"Above my limit"}""";

    @Test
    @DisplayName("a referral is for whoever works the credit decision: agents and Finance are refused (403)")
    void referralIsForCreditDecisionWorkers() throws Exception {
        for (String role : List.of("AGENTS", "FINANCE")) {
            mvc.perform(post("/lending/v1/loans/64/credit-referral").with(as("someone", role))
                            .contentType(MediaType.APPLICATION_JSON).content(REFERRAL))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(creditDecisionService);
    }

    @Test
    @DisplayName("a referral names its recommendation, reason code and comment, and answers with where it went")
    void referralIsRecordedAndAnswered() throws Exception {
        mvc.perform(post("/lending/v1/loans/64/credit-referral").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.recommendation").value("Recommendation is required (APPROVED or REJECTED)"))
                .andExpect(jsonPath("$.data.reasonCode").value("Reason code is required"))
                .andExpect(jsonPath("$.data.comment").value("Comment is required"));
        verifyNoInteractions(creditDecisionService);

        when(creditDecisionService.refer(eq(64L), any())).thenReturn(new CreditDecisionResponse(31L,
                CreditAction.REFERRED, "APPROVE_WITHIN_POLICY", "Meets credit policy", "Above my limit", "cmanager",
                null, "SENIOR_CREDIT_OFFICER", InternalApprovalStatus.APPROVED, "{}", "4d8c"));
        mvc.perform(post("/lending/v1/loans/64/credit-referral").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(REFERRAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Loan referred to SENIOR_CREDIT_OFFICER"))
                .andExpect(jsonPath("$.data.action").value("REFERRED"))
                .andExpect(jsonPath("$.data.referredTo").value("SENIOR_CREDIT_OFFICER"))
                .andExpect(jsonPath("$.data.recommendation").value("APPROVED"));
        org.mockito.ArgumentCaptor<CreditReferralRequest> request =
                org.mockito.ArgumentCaptor.forClass(CreditReferralRequest.class);
        verify(creditDecisionService).refer(eq(64L), request.capture());
        assertThat(request.getValue().getRecommendation()).isEqualTo(InternalApprovalStatus.APPROVED);
    }

    @Test
    @DisplayName("a referral with nowhere to go is a 409, and an approval above the limit a 403, in the service's"
            + " words")
    void limitRefusalsReachTheClient() throws Exception {
        when(creditDecisionService.refer(eq(42L), any())).thenThrow(new ConflictException(
                "Loan 000000042 is within your approval limit; decide it rather than refer it"));
        mvc.perform(post("/lending/v1/loans/42/credit-referral").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(REFERRAL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Loan 000000042 is within your approval limit; decide it rather than refer it"));

        when(creditDecisionService.decide(eq(64L), any())).thenThrow(new AccessDeniedException(
                "Loan 000000064 is for 2659.57, above cmanager's approval limit of 1000.00 (Credit officer);"
                        + " refer it to Senior credit officer or above"));
        mvc.perform(post("/lending/v1/loans/64/credit-decision").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVED","reasonCode":"APPROVE_WITHIN_POLICY","comment":"Fine"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Loan 000000064 is for 2659.57, above cmanager's approval"
                        + " limit of 1000.00 (Credit officer); refer it to Senior credit officer or above"));
    }
}
