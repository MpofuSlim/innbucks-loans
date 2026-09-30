package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import zw.co.innbucks.loans.core.loan.CreditDecisionResponse;
import zw.co.innbucks.loans.core.loan.CreditDecisionService;
import zw.co.innbucks.loans.core.loan.CreditResubmissionRequest;
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
import zw.co.innbucks.loans.web.SigningContexts;

import java.time.LocalDate;
import java.util.List;

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
            description = "Captures an application originated by the signed-in user (FR-SSB-017): it is theirs,"
                    + " their merchant's and priced with their commission, whichever channel it came through;"
                    + " channelId only records that channel. It is lodged with SSB for the payroll deduction, then"
                    + " goes to Credit. Every"
                    + " missing field is reported in one 400. Documents are base64: the payslip and national ID a PDF,"
                    + " PNG, JPEG or GIF, each signature a PNG, JPEG or GIF, each file at most 5 MB. Each must be"
                    + " readable (FR-SSB-005): a damaged, password-protected, blank, blurred or too-small upload is"
                    + " refused, and every refused document is listed in one 400 with its field, reason and a message"
                    + " for the applicant."
                    + " employmentDetail carries where the applicant works (ministry, station, grade, contract"
                    + " type) and the payslip's gross and net pay; payslipDeductions lists each deduction already"
                    + " on the payslip. walletNumber is the InnBucks wallet the loan pays, the mobile number when"
                    + " omitted. Signing (FR-SSB-013): once a loan agreement or SSB deduction authority is published,"
                    + " the application signs it. Show the applicant each instrument from POST /loans/instruments/preview,"
                    + " then send the versions they accepted (loanAgreementVersion, deductionAuthorityVersion), their"
                    + " signature, and the device in the X-Device-Id header; the text signed is kept with the time,"
                    + " device, address and sign-in method (GET /loans/{loanId}/signed-instruments).")
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
                            @ExampleObject(name = "Net above gross", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Net salary cannot exceed gross salary"
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
                            @ExampleObject(name = "Unknown channel", value = ApiExamples.UNKNOWN_CHANNEL),
                            @ExampleObject(name = "Documents refused", value = ApiExamples.DOCUMENTS_REFUSED),
                            @ExampleObject(name = "Not signed", value = ApiExamples.APPLICATION_NOT_SIGNED)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "409", description = "The applicant already has a loan in flight, or accepted wording"
                    + " no longer in force; nothing was created",
                    content = @Content(examples = {
                            @ExampleObject(name = "Loan in flight", value = """
                                    {
                                      "code": "APPLICATION_PENDING",
                                      "message": "You have a pending loan application."
                                    }"""),
                            @ExampleObject(name = "Wording changed", value = ApiExamples.INSTRUMENT_CHANGED)}))
    })
    @Parameters({
            @Parameter(in = ParameterIn.HEADER, name = SigningContexts.DEVICE_ID_HEADER,
                    description = "The signing device, as the app or portal identifies it; required once an instrument"
                            + " is published", example = "a3f1c2e4-7b9d-4e21-9c55-1f0e8d6b2a77"),
            @Parameter(in = ParameterIn.HEADER, name = SigningContexts.SIGNER_AUTHENTICATION_HEADER,
                    description = "How the channel authenticated the applicant who signed, when it did", example = "SUPERAPP_PIN")
    })
    @PostMapping("/loans")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<LoanApplicationResponse> apply(
            // Default + LoanApplicationChecks: an application must carry what the InnBucks step needs,
            // reported in ONE 400. A quote (below) takes the terms alone.
            @Validated({Default.class, LoanApplicationChecks.class}) @RequestBody LoanApplicationRequest request,
            @Parameter(hidden = true) JwtAuthenticationToken authentication, HttpServletRequest httpRequest) {
        // Identifiers only: the body carries the applicant's KYC and base64 documents.
        log.info("Loan application: channel {}, ec {}, amount {}, tenor {}", request.getChannelId(),
                maskEcNumber(request.getEcNumber()), request.getAmount(), request.getTenor());
        return new ApiResult<>("CREATED", "Loan sent for approval",
                loanService.requestLoan(request, SigningContexts.of(httpRequest, authentication)));
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
                    + " newest first. Each carries creditTurnaround: when it reached Credit, when a decision is due and"
                    + " when it escalates, how long it has waited, and whether it is overdue or escalated (see GET"
                    + " /service-levels). GET /loans/{loanId}/credit-workbench gathers what is needed to decide one; the"
                    + " same roles decide it with POST /loans/{loanId}/credit-decision.")
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
            description = "The loan in full, with the current version of each document listed (documents), without"
                    + " content: the content, and every earlier version, come from the loan's documents endpoints, which"
                    + " log each view. A loan outside the caller's scope is answered exactly like one that does not exist.")
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
            description = "CREDIT_MANAGER or SUPER_ADMIN approves, rejects or returns a loan SSB has approved."
                    + " Every decision needs an active reason code for that decision (GET /credit-reason-codes) and a"
                    + " comment, and is kept in the loan's credit decision log with the loan data it was based on."
                    + " Nobody may approve a loan they originated, resubmitted, or are a party to (the applicant,"
                    + " their next of kin, or the holder of the payout wallet). An approval freezes where the loan is"
                    + " paid; a rejection flags the SSB deduction for cancellation; RETURNED sends the loan back to its"
                    + " originator for more information, and while it waits it can only be rejected. The customer is"
                    + " told by SMS; the comment is not sent to them.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded; the loan as it now stands",
                    content = @Content(examples = {
                            @ExampleObject(name = "Approved", value = ApiExamples.LOAN_CREDIT_APPROVED),
                            @ExampleObject(name = "Returned", value = ApiExamples.LOAN_CREDIT_RETURNED)})),
            @ApiResponse(responseCode = "400", description = "Not decidable, a reason code that does not fit, or nowhere to pay it",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "comment": "Comment is required",
                                        "reasonCode": "Reason code is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Reason code for another decision", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Reason code RETURN_PAYSLIP is for RETURNED decisions, not APPROVED"
                                    }"""),
                            @ExampleObject(name = "Unknown reason code", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Unknown reason code APPROVE_OK"
                                    }"""),
                            @ExampleObject(name = "Returned, not resubmitted", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Loan was returned for more information and has not been resubmitted; it can only be rejected"
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
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN, or the caller may not approve this loan",
                    content = @Content(examples = {
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN),
                            @ExampleObject(name = "Originator", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "Loan 000000042 was originated by tmoyo, who cannot also approve it; another credit officer must"
                                    }"""),
                            @ExampleObject(name = "Resubmitter", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "Loan 000000042 was resubmitted by tmoyo, who cannot also approve it; another credit officer must"
                                    }"""),
                            @ExampleObject(name = "Party to the loan", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "cmanager is a party to loan 000000042 and cannot approve it; another credit officer must"
                                    }""")})),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @PostMapping("/loans/{loanId}/credit-decision")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
    public ApiResult<LoanResponse> decide(@PathVariable Long loanId, @Valid @RequestBody CreditDecisionRequest request) {
        log.info("Credit decision {} ({}) on loan {}", request.getDecision(), request.getReasonCode(), loanId);
        LoanResponse loan = creditDecisionService.decide(loanId, request);
        String message = switch (request.getDecision()) {
            case APPROVED -> "Loan approved";
            case REJECTED -> "Loan rejected";
            default -> "Loan returned for more information";
        };
        return ApiResult.ok(message, loan);
    }

    @Operation(summary = "Resubmit a returned loan to Credit",
            description = "Answers a loan Credit returned for more information: the comment carries the answer, and the"
                    + " loan goes back to PENDING in the credit queue. Any user who can read the loan may resubmit it"
                    + " (an agent, only their own loans). Whoever resubmits cannot then approve it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Back in the credit queue; the loan as it now stands",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_RESUBMITTED))),
            @ApiResponse(responseCode = "400", description = "No comment, or the loan is not waiting for more information",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing comment", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "comment": "Comment is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Not returned", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Loan is not waiting for more information (credit status PENDING)"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such loan, or not one the caller may read",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @PostMapping("/loans/{loanId}/credit-resubmission")
    public ApiResult<LoanResponse> resubmit(JwtAuthenticationToken authentication, @PathVariable Long loanId,
                                            @Valid @RequestBody CreditResubmissionRequest request) {
        log.info("Credit resubmission of loan {}", loanId);
        return ApiResult.ok("Loan resubmitted to Credit",
                creditDecisionService.resubmit(loanId, request, readScope(authentication)));
    }

    @Operation(summary = "A loan's credit decision log",
            description = "Every credit action on the loan, oldest first: each decision and each resubmission, with who"
                    + " took it, when, the reason code and comment, and the loan data it was based on (loanSnapshot, with"
                    + " its SHA-256). The log is append-only. Decisions taken before the log existed are carried over"
                    + " with a LEGACY_ reason code and a snapshot that says the data was not captured.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success; an empty list when Credit has not acted on the loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.CREDIT_DECISION_LOG))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @GetMapping("/loans/{loanId}/credit-decisions")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
    public ApiResult<List<CreditDecisionResponse>> creditDecisions(@PathVariable Long loanId) {
        return ApiResult.ok(creditDecisionService.history(loanId));
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
