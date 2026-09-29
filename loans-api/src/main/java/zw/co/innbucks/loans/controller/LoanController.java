package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.DisbursementService;
import zw.co.innbucks.loans.core.ManualDisbursementResponse;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.loan.CreditDecisionRequest;
import zw.co.innbucks.loans.core.loan.CreditDecisionService;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanApplicationChecks;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;
import zw.co.innbucks.loans.core.loan.LoanApplicationResponse;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanQuote;
import zw.co.innbucks.loans.core.loan.LoanQuoteRequest;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.loan.LoanResponse;
import zw.co.innbucks.loans.core.loan.LoanSearchCriteria;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.core.loan.LoanSummaryResponse;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;

import java.time.LocalDate;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;
import static zw.co.innbucks.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl.maskEcNumber;

@Tag(name = "Loans", description = "Applications, quotes, the loan book and the Credit decision. A loan moves"
        + " through four stages, each with its own status: SSB accepts the payroll deduction (ssbApprovalStatus),"
        + " Credit decides (creditApprovalStatus), InnBucks books the loan (bookingStatus) and the payout lands"
        + " (disbursementStatus).")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@Slf4j
public class LoanController {

    private final LoanService loanService;
    private final LoanReadScopeResolver loanReadScopeResolver;
    private final CreditDecisionService creditDecisionService;
    private final DisbursementService disbursementService;

    @Operation(summary = "Apply for a loan",
            description = "Captures an application for the signed-in user's merchant, or for the channel named by"
                    + " channelId. It is lodged with SSB for the payroll deduction, then goes to Credit. Every"
                    + " missing field is reported in one 400. Documents are base64 PDF, PNG, JPEG or GIF.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Accepted and sent for SSB approval",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CREATED",
                              "message": "Loan sent for approval",
                              "data": {
                                "id": 42,
                                "reference": "000000042",
                                "ssbApprovalStatus": "NEW"
                              }
                            }"""))),
            @ApiResponse(responseCode = "400", description = "A missing or invalid field, or a rule the application breaks",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "ecNumber": "EC number is required",
                                        "employmentDetail.employerName": "Employer name is required",
                                        "nextOfKin": "Next of kin is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Invalid EC number", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "EC Number is not valid"
                                    }"""),
                            @ExampleObject(name = "Under 18", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Must be 18+ years"
                                    }"""),
                            @ExampleObject(name = "Amount out of range", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Loan amount should be between 20 and 2000"
                                    }"""),
                            @ExampleObject(name = "Unsafe document", value = """
                                    {
                                      "code": "INVALID_DOCUMENT",
                                      "message": "payslipPicture is not a recognised document type (PDF/PNG/JPEG/GIF)"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "409", description = "The applicant already has a loan in flight; nothing was created",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "APPLICATION_PENDING",
                              "message": "You have a pending loan application."
                            }""")))
    })
    @PostMapping("/loans")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<LoanApplicationResponse> apply(
            // Default + LoanApplicationChecks: an application must carry what the InnBucks step needs,
            // reported in ONE 400. A quote (below) takes the terms alone.
            @Validated({Default.class, LoanApplicationChecks.class}) @RequestBody LoanApplicationRequest request) {
        // Identifiers only: the body carries the applicant's KYC and base64 documents.
        log.info("Loan application: channel {}, ec {}, amount {}, tenor {}", request.getChannelId(),
                maskEcNumber(request.getEcNumber()), request.getAmount(), request.getTenor());
        return new ApiResult<>("CREATED", "Loan sent for approval", loanService.requestLoan(request));
    }

    @Operation(summary = "Quote a loan",
            description = "Prices the terms without applying: amounts, rates and the repayment schedule. Nothing is"
                    + " stored. A quote carries no commission split: that comes from the originator's commission"
                    + " group when the application is made.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": {
                        "principal": 531.91,
                        "tenor": 3,
                        "interestRate": 7,
                        "interestAmount": 76.14,
                        "feeRate": 6,
                        "feeAmount": 31.91,
                        "disbursedAmount": 500.00,
                        "monthlyInstallment": 202.69,
                        "commissionRate": 3,
                        "grossedMonthlyDeduction": 208.96,
                        "agentCommission": 0,
                        "providerCommission": 0,
                        "agentCommissionRate": 0,
                        "providerCommissionRate": 0,
                        "commissionPercentage": false,
                        "amortizationSchedule": [
                          {
                            "paymentNumber": 1,
                            "regularMonthlyPayment": 202.69,
                            "principalPayment": 165.46,
                            "interestPayment": 37.23,
                            "remainingPrincipal": 366.45,
                            "grossedMonthlyPayment": 208.96
                          },
                          {
                            "paymentNumber": 2,
                            "regularMonthlyPayment": 202.69,
                            "principalPayment": 177.04,
                            "interestPayment": 25.65,
                            "remainingPrincipal": 189.41,
                            "grossedMonthlyPayment": 208.96
                          },
                          {
                            "paymentNumber": 3,
                            "regularMonthlyPayment": 202.69,
                            "principalPayment": 189.43,
                            "interestPayment": 13.26,
                            "remainingPrincipal": 0,
                            "grossedMonthlyPayment": 208.96
                          }
                        ]
                      }
                    }"""))),
            @ApiResponse(responseCode = "400", description = "Terms missing or out of range",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing tenor", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "tenor": "Loan tenor is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Tenor out of range", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Loan tenor should be between 1 and 24"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @PostMapping("/loan-quotes")
    public ApiResult<LoanQuote> quote(@Valid @RequestBody LoanQuoteRequest request) {
        log.info("Loan quote: amount {}, tenor {}, type {}", request.amount(), request.tenor(), request.amountType());
        return ApiResult.ok(loanService.calculate(request, null));
    }

    @Operation(summary = "List loans",
            description = "One page of loans, newest first. SUPER_ADMIN, CREDIT_MANAGER and FINANCE see every"
                    + " merchant's loans; anyone else only the loans they originated. Every filter is optional;"
                    + " a merchantCode outside the caller's scope matches nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_PAGE))),
            @ApiResponse(responseCode = "400", description = "A filter value that is not one of the allowed ones",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'ssbApprovalStatus'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @GetMapping("/loans")
    public ApiResult<PageResponse<LoanSummaryResponse>> listLoans(
            JwtAuthenticationToken authentication,
            @Parameter(description = "NEW, PROCESSING, APPROVED, REJECTED, PAID or FAILED")
            @RequestParam(required = false) LoanApprovalStatus ssbApprovalStatus,
            @Parameter(description = "PENDING, APPROVED or REJECTED")
            @RequestParam(required = false) InternalApprovalStatus creditApprovalStatus,
            @Parameter(description = "PENDING, SUCCESS or FAILED")
            @RequestParam(required = false) LoanDisbursementStatus disbursementStatus,
            @RequestParam(required = false) String merchantCode,
            @Parameter(description = "Created on or after this market day, yyyy-MM-dd", example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "Created on or before this market day, yyyy-MM-dd", example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100; 20 when omitted", example = "20")
            @RequestParam(required = false) Integer size) {
        LoanSearchCriteria criteria = LoanSearchCriteria.builder()
                .ssbApprovalStatus(ssbApprovalStatus)
                .creditApprovalStatus(creditApprovalStatus)
                .disbursementStatus(disbursementStatus)
                .merchantCode(merchantCode)
                .fromDate(fromDate)
                .toDate(toDate)
                .build();
        return ApiResult.ok(PageResponse.from(
                loanService.findLoans(criteria, readScope(authentication), Paging.of(page, size))));
    }

    @Operation(summary = "List loans awaiting a Credit decision",
            description = "SUPER_ADMIN and CREDIT_MANAGER: the loans SSB has approved and Credit has not yet decided,"
                    + " newest first. The same roles decide them with POST /loans/{loanId}/credit-decision.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_PAGE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN or CREDIT_MANAGER",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/loans/pending-credit-decision")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CREDIT_MANAGER')")
    public ApiResult<PageResponse<LoanSummaryResponse>> listAwaitingCreditDecision(
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100; 20 when omitted", example = "20")
            @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(loanService.findLoans(LoanSearchCriteria.awaitingCreditDecision(),
                LoanReadScope.platform(), Paging.of(page, size))));
    }

    @Operation(summary = "Get a loan",
            description = "The loan in full, documents included. A loan outside the caller's scope is answered"
                    + " exactly like one that does not exist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_AWAITING_CREDIT))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such loan, or not one the caller may read",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @GetMapping("/loans/{loanId}")
    public ApiResult<LoanResponse> getLoan(JwtAuthenticationToken authentication, @PathVariable Long loanId) {
        return ApiResult.ok(loanService.getLoan(loanId, readScope(authentication)));
    }

    @Operation(summary = "Decide a loan (Credit)",
            description = "CREDIT_MANAGER or SUPER_ADMIN approves or rejects a loan SSB has approved. Whoever"
                    + " originated the loan cannot approve it. An approval freezes where the loan is paid (the"
                    + " customer's wallet, or the merchant's account as it stands now); a rejection flags the SSB"
                    + " deduction for cancellation. The customer is told by SMS; the comment is not sent to them.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded; the loan as it now stands",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_CREDIT_APPROVED))),
            @ApiResponse(responseCode = "400", description = "Not decidable, or nowhere to pay it",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing decision", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "decision": "Decision is required (APPROVED or REJECTED)"
                                      }
                                    }"""),
                            @ExampleObject(name = "Already decided", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Loan has already been approved"
                                    }"""),
                            @ExampleObject(name = "SSB has not approved", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Loan with status PROCESSING cannot be approved"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN, or the caller originated the loan",
                    content = @Content(examples = {
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN),
                            @ExampleObject(name = "Originator", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "Loan 000000042 was originated by tmoyo, who cannot also approve it; another credit officer must"
                                    }""")})),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @PostMapping("/loans/{loanId}/credit-decision")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
    public ApiResult<LoanResponse> decide(@PathVariable Long loanId, @Valid @RequestBody CreditDecisionRequest request) {
        log.info("Credit decision {} on loan {}", request.getDecision(), loanId);
        LoanResponse loan = creditDecisionService.decide(loanId, request);
        return ApiResult.ok(request.getDecision() == InternalApprovalStatus.APPROVED ? "Loan approved" : "Loan rejected",
                loan);
    }

    @Operation(summary = "Pay a loan manually (recovery)",
            description = "SUPER_ADMIN only, for recovery: pays a loan through the InnBucks deposit rail when SSB and"
                    + " Credit approved it and InnBucks definitively refused its booking (the booking is what normally"
                    + " pays it). Every attempt for a loan carries one reference, MD-<loan reference>. An attempt"
                    + " whose outcome is unknown (a timeout, a 5xx) is IN_DOUBT and blocks every further attempt"
                    + " until it has been confirmed with InnBucks.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempted: DISBURSED, REFUSED (nothing paid; may be tried"
                    + " again) or IN_DOUBT (confirm with InnBucks using the reference)",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "OK",
                              "message": "Success",
                              "data": {
                                "outcome": "DISBURSED",
                                "reference": "MD-000000042",
                                "message": "Paid by manual recovery payout MD-000000042 (InnBucks auth 734512)"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Not eligible for a manual payout; nothing was sent",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "DISBURSEMENT_NOT_ALLOWED",
                              "message": "Loan 000000042 is not credit-approved (status PENDING)"
                            }""")))
    })
    @PostMapping("/loans/{loanId}/disbursements")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<ManualDisbursementResponse> disburse(@PathVariable Long loanId) {
        log.info("Manual recovery payout requested for loan {}", loanId);
        // The service refuses (409) anything the pre-approved booking may still pay, and never pays twice:
        // one stable reference per loan, written ahead of the InnBucks call.
        return ApiResult.ok(disbursementService.disburse(loanId));
    }

    private LoanReadScope readScope(JwtAuthenticationToken authentication) {
        return loanReadScopeResolver.resolve(authentication.getToken());
    }
}
