package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.loan.LoanResponse;
import zw.co.innbucks.loans.core.loan.PayslipReviewRequest;
import zw.co.innbucks.loans.core.loan.PayslipReviewResponse;
import zw.co.innbucks.loans.core.loan.PayslipReviewService;
import zw.co.innbucks.loans.core.loan.PayslipReviewStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Payslip reviews", description = "Applications held back from SSB because their payslip raised a"
        + " concern (FR-SSB-007): the same payslip file already on another application, under another applicant"
        + " or the same one, or captured deductions that add up to more than gross less net. A held application"
        + " is lodged with SSB only once a credit officer clears it. The originator and the customer are not told.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@Slf4j
public class PayslipReviewController {

    private final PayslipReviewService payslipReviewService;

    @Operation(summary = "List applications waiting for payslip review",
            description = "Oldest first. Each finding names the other application involved, how it stands, and whether"
                    + " it was itself confirmed as fraud.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success; an empty list when nothing is waiting",
                    content = @Content(examples = @ExampleObject(ApiExamples.PAYSLIP_REVIEW_QUEUE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not entitled to see the PAYSLIP_REVIEW stage"
                    + " (CREDIT_MANAGER and SUPER_ADMIN by default; see GET /workflow-stages)",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/payslip-reviews")
    @PreAuthorize("isAuthenticated() and @workflowAccess.may(authentication, 'PAYSLIP_REVIEW', 'VIEW')")
    public ApiResult<List<PayslipReviewResponse>> queue() {
        return ApiResult.ok(payslipReviewService.queue());
    }

    @Operation(summary = "Decide a payslip review",
            description = "CLEARED releases the application to be lodged with SSB as usual. CONFIRMED upholds the"
                    + " suspicion: the application is rejected with reason code REJECT_SUSPECTED_FRAUD, the rejection is"
                    + " kept in the loan's credit decision log, and the customer receives the ordinary decline SMS."
                    + " Nobody may clear an application they originated or are a party to; anyone may confirm.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decided; the loan as it now stands",
                    content = @Content(examples = {
                            @ExampleObject(name = "Cleared", value = ApiExamples.LOAN_PAYSLIP_REVIEW_CLEARED),
                            @ExampleObject(name = "Confirmed", value = ApiExamples.LOAN_PAYSLIP_REVIEW_CONFIRMED)})),
            @ApiResponse(responseCode = "400", description = "Missing fields, or not an outcome",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "comment": "Comment is required",
                                        "outcome": "Outcome is required (CLEARED or CONFIRMED)"
                                      }
                                    }"""),
                            @ExampleObject(name = "Not an outcome", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Invalid outcome: a review must be CLEARED or CONFIRMED"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not entitled to work the PAYSLIP_REVIEW stage"
                    + " (CREDIT_MANAGER and SUPER_ADMIN by default; see GET /workflow-stages), or the caller may not"
                    + " clear this application",
                    content = @Content(examples = {
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN),
                            @ExampleObject(name = "Originator", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "Loan 000000057 was originated by tmoyo, who cannot also clear its payslip review; another credit officer must"
                                    }"""),
                            @ExampleObject(name = "Party to the loan", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "cmanager is a party to loan 000000057 and cannot clear its payslip review; another credit officer must"
                                    }""")})),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "Loan 57 not found"
                            }"""))),
            @ApiResponse(responseCode = "409", description = "The loan is not waiting for payslip review, or the stage"
                    + " is EXCLUSIVE and its item is assigned to someone else",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not waiting", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan 000000057 has no payslip review pending"
                                    }"""),
                            @ExampleObject(name = "Assigned to someone else", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan 000000057's Payslip review is assigned to rnyathi; only they can act on it until it is released or reassigned"
                                    }""")}))
    })
    @PostMapping("/loans/{loanId}/payslip-review")
    @PreAuthorize("isAuthenticated() and @workflowAccess.may(authentication, 'PAYSLIP_REVIEW', 'WORK')")
    public ApiResult<LoanResponse> review(@PathVariable Long loanId, @Valid @RequestBody PayslipReviewRequest request) {
        log.info("Payslip review {} on loan {}", request.getOutcome(), loanId);
        LoanResponse loan = payslipReviewService.review(loanId, request);
        return ApiResult.ok(request.getOutcome() == PayslipReviewStatus.CONFIRMED
                ? "Suspected fraud confirmed; the application is rejected"
                : "Payslip review cleared; the application goes on to SSB", loan);
    }
}
