package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import zw.co.innbucks.loans.core.DisbursementService;
import zw.co.innbucks.loans.core.exception.LoanApprovalException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.PendingApplicationException;
import zw.co.innbucks.loans.core.loan.ContractType;
import zw.co.innbucks.loans.core.loan.CreditDecisionRequest;
import zw.co.innbucks.loans.core.loan.CreditDecisionService;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanAmountType;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;
import zw.co.innbucks.loans.core.loan.LoanApplicationResponse;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanQuote;
import zw.co.innbucks.loans.core.loan.LoanQuoteRequest;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.loan.LoanResponse;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.core.loan.PayslipDeduction;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The wire contract the portal codes against: exact status + {@code {code, message, data}}
 * bodies for the loan application, the quote and the Credit decision. The real controller + the
 * real {@link GlobalExceptionHandler} + a real validator, services mocked: no database, no Spring
 * context, no security filter chain.
 */
class LoanApplicationWebContractTest {

    private static final String IDENTITY = """
            {"amount":500.00,"tenor":6,"ecNumber":"1234567A","mobileNumber":"0772123123",
             "nationalIdNumber":"63-1234567A63","dateOfBirth":"1990-05-14"}
            """;

    private static final String COMPLETE = """
            {"amount":500.00,"tenor":6,"ecNumber":"1234567A","mobileNumber":"0772123123",
             "nationalIdNumber":"63-1234567A63","dateOfBirth":"1990-05-14",
             "firstName":"James","lastName":"Mufambanaayo","maritalStatus":"MARRIED","placeOfBirth":"Harare",
             "loanPurpose":"HOME_IMPROVEMENT","lineOfBusiness":"SERVICES",
             "numberOfDependants":3,"numberOfChildren":2,
             "address":{"street":"123 Samora Machel Ave","city":"Harare"},
             "walletNumber":"0712345678",
             "employmentDetail":{"employerName":"Government of Zimbabwe","employeeNumber":"EMP-001",
                                 "ministry":"Ministry of Health and Child Care","station":"Mutare Provincial Hospital",
                                 "grade":"D2","contractType":"PERMANENT",
                                 "employmentStartDate":"2022-01-01","grossSalary":1500.00,"netSalary":1100.00},
             "payslipDeductions":[{"beneficiary":"ZIMRA PAYE","amount":210.00},
                                  {"beneficiary":"CBZ personal loan","amount":150.00}],
             "nextOfKin":{"firstName":"Jane","mobileNumber":"0772321321","relationship":"SPOUSE",
                          "address":{"street":"123 Samora Machel Ave","city":"Harare"}}}
            """;

    private static final String QUOTE = """
            {"amount":500.00,"tenor":6}
            """;

    private LoanService loanService;
    private CreditDecisionService creditDecisionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        loanService = mock(LoanService.class);
        creditDecisionService = mock(CreditDecisionService.class);

        LoanController controller = new LoanController(loanService, mock(LoanReadScopeResolver.class),
                creditDecisionService, mock(DisbursementService.class));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    // --- POST /lending/v1/loans ----------------------------------------------------------------

    @Test
    @DisplayName("an application with only the terms and identity → ONE 400 naming every field InnBucks needs")
    void applicationWithOnlyIdentityIs400() throws Exception {
        mvc.perform(post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON).content(IDENTITY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.data.length()").value(9))
                .andExpect(jsonPath("$.data.address").value("Address is required"))
                .andExpect(jsonPath("$.data.employmentDetail").value("Employment detail is required"))
                .andExpect(jsonPath("$.data.firstName").value("First name is required"))
                .andExpect(jsonPath("$.data.lastName").value("Last name is required"))
                .andExpect(jsonPath("$.data.lineOfBusiness").value("Line of business is required"))
                .andExpect(jsonPath("$.data.loanPurpose").value("Loan purpose is required"))
                .andExpect(jsonPath("$.data.maritalStatus").value("Marital status is required"))
                .andExpect(jsonPath("$.data.nextOfKin").value("Next of kin is required"))
                .andExpect(jsonPath("$.data.placeOfBirth").value("Place of birth is required"));
        verifyNoInteractions(loanService);
    }

    @Test
    @DisplayName("a partial next of kin → 400 keyed by the nested field paths")
    void partialNextOfKinIs400WithNestedPaths() throws Exception {
        String body = COMPLETE.replace(
                "\"nextOfKin\":{\"firstName\":\"Jane\",\"mobileNumber\":\"0772321321\",\"relationship\":\"SPOUSE\",",
                "\"nextOfKin\":{\"firstName\":\"Jane\",");
        mvc.perform(post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data['nextOfKin.mobileNumber']").value("Next of kin mobile number is required"))
                .andExpect(jsonPath("$.data['nextOfKin.relationship']").value("Next of kin relationship is required"));
    }

    @Test
    @DisplayName("a complete application → 201 CREATED with the new loan's id and reference; every field binds")
    void completeApplicationIsCreated() throws Exception {
        when(loanService.requestLoan(any()))
                .thenReturn(new LoanApplicationResponse(42L, "000000042", LoanApprovalStatus.NEW));

        mvc.perform(post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON).content(COMPLETE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message").value("Loan sent for approval"))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.reference").value("000000042"))
                .andExpect(jsonPath("$.data.ssbApprovalStatus").value("NEW"));

        ArgumentCaptor<LoanApplicationRequest> bound = ArgumentCaptor.forClass(LoanApplicationRequest.class);
        verify(loanService).requestLoan(bound.capture());
        LoanApplicationRequest request = bound.getValue();
        assertThat(request.getEcNumber()).isEqualTo("1234567A");
        assertThat(request.getNationalIdNumber()).isEqualTo("63-1234567A63");
        assertThat(request.getFirstName()).isEqualTo("James");
        assertThat(request.getLastName()).isEqualTo("Mufambanaayo");
        assertThat(request.getNumberOfDependants()).isEqualTo(3);
        assertThat(request.getNumberOfChildren()).isEqualTo(2);
        assertThat(request.getAmountType()).isEqualTo(LoanAmountType.NET_OF_FEES);
        assertThat(request.getWalletNumber()).isEqualTo("0712345678");
        assertThat(request.getEmploymentDetail().getMinistry()).isEqualTo("Ministry of Health and Child Care");
        assertThat(request.getEmploymentDetail().getStation()).isEqualTo("Mutare Provincial Hospital");
        assertThat(request.getEmploymentDetail().getGrade()).isEqualTo("D2");
        assertThat(request.getEmploymentDetail().getContractType()).isEqualTo(ContractType.PERMANENT);
        assertThat(request.getEmploymentDetail().getNetSalary()).isEqualByComparingTo("1100.00");
        assertThat(request.getPayslipDeductions()).containsExactly(
                new PayslipDeduction("ZIMRA PAYE", new BigDecimal("210.00")),
                new PayslipDeduction("CBZ personal loan", new BigDecimal("150.00")));
    }

    @Test
    @DisplayName("a payslip deduction with no beneficiary → 400 keyed by its index")
    void badPayslipDeductionIs400() throws Exception {
        mvc.perform(post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE.replace("\"beneficiary\":\"CBZ personal loan\"", "\"beneficiary\":\"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data['payslipDeductions[1].beneficiary']").value("Deduction beneficiary is required"));
        verifyNoInteractions(loanService);
    }

    @Test
    @DisplayName("an unknown contract type → 400 MALFORMED_REQUEST")
    void unknownContractTypeIs400() throws Exception {
        mvc.perform(post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE.replace("\"PERMANENT\"", "\"Permanent\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("an applicant with a loan in flight → 409 APPLICATION_PENDING, not a 200 with a rejected status")
    void pendingApplicationIs409() throws Exception {
        when(loanService.requestLoan(any())).thenThrow(new PendingApplicationException(17L));

        mvc.perform(post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON).content(COMPLETE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_PENDING"))
                .andExpect(jsonPath("$.message").value("You have a pending loan application."))
                // The other loan is the applicant's, not necessarily one the caller may read.
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("a rule the application breaks → 400 INVALID_REQUEST with the rule's message")
    void ruleViolationIs400() throws Exception {
        when(loanService.requestLoan(any())).thenThrow(new IllegalArgumentException("EC Number is not valid"));

        mvc.perform(post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON).content(COMPLETE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("EC Number is not valid"));
    }

    @Test
    @DisplayName("an enum sent as its label (\"Married\") → 400 MALFORMED_REQUEST")
    void unreadableBodyIs400() throws Exception {
        mvc.perform(post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE.replace("\"MARRIED\"", "\"Married\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed request body - check enum values and yyyy-MM-dd dates"));
    }

    @Test
    @DisplayName("a non-Zimbabwean-mobile number → 400 on the mobileNumber field")
    void badMobileIs400() throws Exception {
        mvc.perform(post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE.replace("\"mobileNumber\":\"0772123123\"", "\"mobileNumber\":\"0242123456\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.mobileNumber").value(
                        "must be a Zimbabwean mobile number, e.g. 0772123123 or +263772123123"));
    }

    // --- POST /lending/v1/loan-quotes ------------------------------------------------------------

    @Test
    @DisplayName("a quote needs only amount and tenor → 200 in the envelope")
    void quoteNeedsOnlyTheTerms() throws Exception {
        when(loanService.calculate(any(), any())).thenReturn(LoanQuote.builder().tenor(6).build());

        mvc.perform(post("/lending/v1/loan-quotes").contentType(MediaType.APPLICATION_JSON).content(QUOTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.tenor").value(6))
                // Unset figures are left out, not sent as null.
                .andExpect(jsonPath("$.data.startDate").doesNotExist());
    }

    @Test
    @DisplayName("an omitted amountType binds as NET_OF_FEES: the documented default, and what decides the principal")
    void omittedAmountTypeDefaultsToNetOfFees() throws Exception {
        when(loanService.calculate(any(), any())).thenReturn(LoanQuote.builder().tenor(6).build());

        mvc.perform(post("/lending/v1/loan-quotes").contentType(MediaType.APPLICATION_JSON).content(QUOTE))
                .andExpect(status().isOk());

        ArgumentCaptor<LoanQuoteRequest> bound = ArgumentCaptor.forClass(LoanQuoteRequest.class);
        verify(loanService).calculate(bound.capture(), isNull());
        assertThat(bound.getValue().amountType()).isEqualTo(LoanAmountType.NET_OF_FEES);
    }

    @Test
    @DisplayName("a quote with no tenor → 400 on the tenor field")
    void quoteWithoutTenorIs400() throws Exception {
        mvc.perform(post("/lending/v1/loan-quotes").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.tenor").value("Loan tenor is required"));
        verifyNoInteractions(loanService);
    }

    // --- POST /lending/v1/loans/{loanId}/credit-decision ----------------------------------------

    private static final String APPROVAL =
            "{\"decision\":\"APPROVED\",\"reasonCode\":\"APPROVE_WITHIN_POLICY\",\"comment\":\"Verified\"}";

    @Test
    @DisplayName("a decision answers with the loan and names the decision in the message")
    void decisionAnswersWithTheLoan() throws Exception {
        LoanResponse loan = new LoanResponse();
        loan.setId(42L);
        loan.setCreditApprovalStatus(InternalApprovalStatus.REJECTED);
        when(creditDecisionService.decide(eq(42L), any())).thenReturn(loan);

        mvc.perform(post("/lending/v1/loans/42/credit-decision").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED\",\"reasonCode\":\"REJECT_AFFORDABILITY\","
                                + "\"comment\":\"Deduction capacity too low\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Loan rejected"))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.creditApprovalStatus").value("REJECTED"));

        ArgumentCaptor<CreditDecisionRequest> bound = ArgumentCaptor.forClass(CreditDecisionRequest.class);
        verify(creditDecisionService).decide(eq(42L), bound.capture());
        assertThat(bound.getValue().getDecision()).isEqualTo(InternalApprovalStatus.REJECTED);
        assertThat(bound.getValue().getReasonCode()).isEqualTo("REJECT_AFFORDABILITY");
        assertThat(bound.getValue().getComment()).isEqualTo("Deduction capacity too low");
    }

    @Test
    @DisplayName("an empty decision → 400 on the decision, reason code and comment fields at once")
    void missingDecisionIs400() throws Exception {
        mvc.perform(post("/lending/v1/loans/42/credit-decision").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.decision").value("Decision is required (APPROVED, REJECTED or RETURNED)"))
                .andExpect(jsonPath("$.data.reasonCode").value("Reason code is required"))
                .andExpect(jsonPath("$.data.comment").value("Comment is required"));
        verifyNoInteractions(creditDecisionService);
    }

    @Test
    @DisplayName("a decision the loan's state refuses → 400 with the reason")
    void decisionRefusalIs400() throws Exception {
        when(creditDecisionService.decide(eq(42L), any()))
                .thenThrow(new LoanApprovalException("Loan has already been rejected"));

        mvc.perform(post("/lending/v1/loans/42/credit-decision").contentType(MediaType.APPLICATION_JSON)
                        .content(APPROVAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Loan has already been rejected"));
    }

    @Test
    @DisplayName("a decision on an unknown loan → 404 in the envelope")
    void decisionOnUnknownLoanIs404() throws Exception {
        when(creditDecisionService.decide(eq(7L), any())).thenThrow(new NotFoundException("Loan 7 not found"));

        mvc.perform(post("/lending/v1/loans/7/credit-decision").contentType(MediaType.APPLICATION_JSON)
                        .content(APPROVAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Loan 7 not found"));
    }

    @Test
    @DisplayName("an approval by the loan's originator → 403 naming why (maker-checker)")
    void originatorApprovalIs403() throws Exception {
        when(creditDecisionService.decide(eq(42L), any())).thenThrow(new AccessDeniedException(
                "Loan 000000042 was originated by credit.manager, who cannot also approve it; another credit officer must"));

        mvc.perform(post("/lending/v1/loans/42/credit-decision").contentType(MediaType.APPLICATION_JSON)
                        .content(APPROVAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Loan 000000042 was originated by credit.manager, who cannot"
                        + " also approve it; another credit officer must"));
    }
}
