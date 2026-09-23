package zw.co.reikan.loans.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import zw.co.reikan.loans.advice.RestExceptionHandler;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.exception.LoanApprovalException;
import zw.co.reikan.loans.core.exception.NotFoundException;
import zw.co.reikan.loans.core.loan.InternalApprovalService;
import zw.co.reikan.loans.core.loan.LoanAmountType;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanDetails;
import zw.co.reikan.loans.core.loan.LoanRequest;
import zw.co.reikan.loans.core.loan.LoanService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The wire contract the portal codes against: exact status + {@code {status, error}}
 * bodies for the loan application, the calculator and credit-manager approval.
 * Real controllers + the real {@link RestExceptionHandler} + a real validator,
 * services mocked — no database, no Spring context, no security filter chain.
 */
class LoanApplicationWebContractTest {

    private static final String QUOTE = """
            {"amount":500.00,"tenor":6,"ecnumber":"1234567A","mobileNumber":"0772123123",
             "nationalId":"63-1234567A63","dateOfBirth":"1990-05-14"}
            """;

    private static final String COMPLETE = """
            {"amount":500.00,"tenor":6,"ecnumber":"1234567A","mobileNumber":"0772123123",
             "nationalId":"63-1234567A63","dateOfBirth":"1990-05-14",
             "fname":"James","lname":"Mufambanaayo","maritalStatus":"MARRIED","placeOfBirth":"Harare",
             "purposeOfLoan":"HOME_IMPROVEMENT","lineOfBusiness":"SERVICES",
             "address":{"street":"123 Samora Machel Ave","city":"Harare"},
             "employmentDetail":{"employerName":"Mutare City Council","employeeNumber":"EMP-001",
                                 "employmentStartDate":"2022-01-01","grossSalary":1500.00},
             "nextOfKin":{"firstName":"Jane","mobileNumber":"0772321321","relationship":"SPOUSE",
                          "address":{"street":"123 Samora Machel Ave","city":"Harare"}}}
            """;

    private LoanService loanService;
    private InternalApprovalService approvalService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        loanService = mock(LoanService.class);
        approvalService = mock(InternalApprovalService.class);

        InternalLoanApplicationController applications = new InternalLoanApplicationController();
        ReflectionTestUtils.setField(applications, "loanService", loanService);
        LoanManagementController management = new LoanManagementController();
        ReflectionTestUtils.setField(management, "internalApprovalService", approvalService);
        ReflectionTestUtils.setField(management, "disbursementService", mock(DisbursementService.class));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(applications, management)
                .setControllerAdvice(new RestExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("POST /api/loans with only the loan terms → ONE 400 naming every field InnBucks needs")
    void applicationWithOnlyTermsIs400() throws Exception {
        mvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(QUOTE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value(
                        "address: Address is required; "
                                + "employmentDetail: Employment detail is required; "
                                + "fname: First name is required; "
                                + "lineOfBusiness: Line of business is required; "
                                + "lname: Last name is required; "
                                + "maritalStatus: Marital status is required; "
                                + "nextOfKin: Next of kin is required; "
                                + "placeOfBirth: Place of birth is required; "
                                + "purposeOfLoan: Purpose of loan is required"));
        verifyNoInteractions(loanService);
    }

    @Test
    @DisplayName("POST /api/loans with a partial next of kin → 400 with nested field paths")
    void partialNextOfKinIs400WithNestedPaths() throws Exception {
        String body = COMPLETE.replace(
                "\"nextOfKin\":{\"firstName\":\"Jane\",\"mobileNumber\":\"0772321321\",\"relationship\":\"SPOUSE\",",
                "\"nextOfKin\":{\"firstName\":\"Jane\",");
        mvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "nextOfKin.mobileNumber: Next of kin mobile number is required; "
                                + "nextOfKin.relationship: Next of kin relationship is required"));
    }

    @Test
    @DisplayName("POST /api/loans complete → 200 from the service")
    void completeApplicationIsAccepted() throws Exception {
        when(loanService.requestLoan(any())).thenReturn(LoanResponse.builder()
                .loanApprovalStatus(LoanApprovalStatus.NEW).internalReference("000000042")
                .message("Loan Sent For Approval").build());

        mvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(COMPLETE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internalReference").value("000000042"));
    }

    @Test
    @DisplayName("POST /api/loans/calculate with only the terms → 200: a quote needs no applicant details")
    void calculatorStillQuotesFromTheTerms() throws Exception {
        when(loanService.calculate(any(), any())).thenReturn(LoanDetails.builder().tenor(6).build());

        mvc.perform(post("/api/loans/calculate").contentType(MediaType.APPLICATION_JSON).content(QUOTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenor").value(6));
    }

    @Test
    @DisplayName("an omitted `type` binds as NET_OF_FEES — the documented default, and what decides the principal")
    void omittedTypeDefaultsToNetOfFees() throws Exception {
        when(loanService.calculate(any(), any())).thenReturn(LoanDetails.builder().tenor(6).build());

        mvc.perform(post("/api/loans/calculate").contentType(MediaType.APPLICATION_JSON).content(QUOTE))
                .andExpect(status().isOk());

        ArgumentCaptor<LoanRequest> bound = ArgumentCaptor.forClass(LoanRequest.class);
        verify(loanService).calculate(bound.capture(), any());
        assertThat(bound.getValue().getType()).isEqualTo(LoanAmountType.NET_OF_FEES);
    }

    @Test
    @DisplayName("a non-Zimbabwean-mobile number → 400 on the calculator and the application alike")
    void badMobileIs400() throws Exception {
        mvc.perform(post("/api/loans/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content(QUOTE.replace("0772123123", "0242123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "mobileNumber: must be a Zimbabwean mobile number, e.g. 0772123123 or +263772123123"));
    }

    @Test
    @DisplayName("an enum sent as its label (\"Married\") → 400, no longer a 500")
    void unreadableBodyIs400() throws Exception {
        mvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE.replace("\"MARRIED\"", "\"Married\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request body — check enum values and yyyy-MM-dd dates"));
    }

    @Test
    @DisplayName("POST /api/loans/{id}/approve refused by the loan's state → 400 with the reason, no longer a 500")
    void approvalRefusalIs400() throws Exception {
        when(approvalService.approveLoan(any(), eq(42L)))
                .thenThrow(new LoanApprovalException("Loan has already been rejected"));

        mvc.perform(post("/api/loans/42/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Loan has already been rejected"));
    }

    @Test
    @DisplayName("POST /api/loans/{id}/approve on an unknown loan → 404 in the error envelope")
    void approvalOfUnknownLoanIs404() throws Exception {
        when(approvalService.approveLoan(any(), eq(7L))).thenThrow(new NotFoundException("Loan 7 not found"));

        mvc.perform(post("/api/loans/7/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Loan 7 not found"));
    }
}
