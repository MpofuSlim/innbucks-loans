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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.staff.loan.ProposeStaffLoanWriteOffRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanWriteOffDecision;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanWriteOffDecisionRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanWriteOffKind;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanWriteOffResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanWriteOffService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanWriteOffStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;
import zw.co.innbucks.loans.web.StaffLoanWriteOffApiExamples;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff loan write-offs", description = "Writing off a Staff Grocery Loan, and reversing a write-off"
        + " (FR-GEN-011), under maker-checker: CREDIT_MANAGER, FINANCE or SUPER_ADMIN proposes, with a reason; FINANCE"
        + " or a SUPER_ADMIN approves or rejects, never the proposer. Only a paid-out loan whose voucher can no longer be"
        + " spent can be written off. A written-off loan is still owed, and it stops the borrower taking another Staff"
        + " Grocery Loan unless Credit overrides (FR-SGL-014). A reversal puts a loan written off in error back to"
        + " DISBURSED. Audited, the loan's change of state too.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffLoanWriteOffController {

    private static final String PROPOSERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')";
    private static final String CHECKERS = "hasAnyRole('FINANCE','SUPER_ADMIN')";
    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')";

    private final StaffLoanWriteOffService writeOffService;

    @Operation(summary = "Propose writing off a staff loan, or reversing its write-off",
            description = "CREDIT_MANAGER, FINANCE or SUPER_ADMIN. kind WRITE_OFF is for a paid-out (DISBURSED) loan"
                    + " whose voucher can no longer be spent; a loan awaiting disbursement is cancelled instead."
                    + " kind WRITE_OFF_REVERSAL is for a WRITTEN_OFF loan, and is refused while the borrower holds"
                    + " another open loan. amount is what the loan owes as loans knows it. One request per loan may"
                    + " wait at a time. Nothing changes until FINANCE or a SUPER_ADMIN approves it. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Proposed",
                    content = @Content(examples = @ExampleObject(StaffLoanWriteOffApiExamples.PROPOSED))),
            @ApiResponse(responseCode = "400", description = "A missing or invalid field",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "kind": "Kind is required (WRITE_OFF or WRITE_OFF_REVERSAL)",
                                "reason": "Reason is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(StaffLoanWriteOffApiExamples.LOAN_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "The loan does not allow it now, or a request already"
                    + " waits for it",
                    content = @Content(examples = {
                            @ExampleObject(name = "Voucher can still be spent",
                                    value = StaffLoanWriteOffApiExamples.VOUCHER_OPEN),
                            @ExampleObject(name = "Not paid out", value = StaffLoanWriteOffApiExamples.NOT_PAID_OUT),
                            @ExampleObject(name = "Nothing to reverse",
                                    value = StaffLoanWriteOffApiExamples.NOTHING_TO_REVERSE),
                            @ExampleObject(name = "Would be a second open loan",
                                    value = StaffLoanWriteOffApiExamples.SECOND_OPEN_LOAN),
                            @ExampleObject(name = "Request waiting",
                                    value = StaffLoanWriteOffApiExamples.PENDING_EXISTS)}))
    })
    @PostMapping("/staff-loan-write-offs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PROPOSERS)
    public ApiResult<StaffLoanWriteOffResponse> propose(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(StaffLoanWriteOffApiExamples.PROPOSAL)))
            @Valid @RequestBody ProposeStaffLoanWriteOffRequest request) {
        return new ApiResult<>("CREATED", "Write-off request proposed; it applies once FINANCE or a SUPER_ADMIN"
                + " approves it", writeOffService.propose(request));
    }

    @Operation(summary = "Approve or reject a write-off request",
            description = "FINANCE or SUPER_ADMIN, never whoever proposed it. A rejection needs a comment. Approval"
                    + " checks the loan again and applies the change at once: DISBURSED becomes WRITTEN_OFF, or a"
                    + " reversal puts it back to DISBURSED. Audited, on the request and on the loan.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decided",
                    content = @Content(examples = @ExampleObject(StaffLoanWriteOffApiExamples.APPROVED))),
            @ApiResponse(responseCode = "400", description = "No decision, or a rejection without a reason",
                    content = @Content(examples = {
                            @ExampleObject(name = "No decision", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "decision": "Decision is required (APPROVED or REJECTED)"
                                      }
                                    }"""),
                            @ExampleObject(name = "Rejection without a reason", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "A reason is required to reject a write-off request"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not FINANCE or SUPER_ADMIN, or the caller proposed it",
                    content = @Content(examples = {
                            @ExampleObject(name = "Own proposal", value = StaffLoanWriteOffApiExamples.OWN),
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN)})),
            @ApiResponse(responseCode = "404", description = "No such request",
                    content = @Content(examples = @ExampleObject(StaffLoanWriteOffApiExamples.NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already decided or withdrawn, or the loan no longer"
                    + " allows it",
                    content = @Content(examples = {
                            @ExampleObject(name = "Already decided",
                                    value = StaffLoanWriteOffApiExamples.ALREADY_DECIDED),
                            @ExampleObject(name = "Would be a second open loan",
                                    value = StaffLoanWriteOffApiExamples.SECOND_OPEN_LOAN)}))
    })
    @PostMapping("/staff-loan-write-offs/{writeOffId}/decision")
    @PreAuthorize(CHECKERS)
    public ApiResult<StaffLoanWriteOffResponse> decide(
            @PathVariable Long writeOffId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(StaffLoanWriteOffApiExamples.APPROVAL)))
            @Valid @RequestBody StaffLoanWriteOffDecisionRequest request) {
        StaffLoanWriteOffResponse decided = writeOffService.decide(writeOffId, request);
        String message;
        if (request.getDecision() == StaffLoanWriteOffDecision.REJECTED) {
            message = "Write-off request rejected";
        } else if (decided.kind() == StaffLoanWriteOffKind.WRITE_OFF) {
            message = "Staff loan " + decided.loanReference() + " written off";
        } else {
            message = "Staff loan " + decided.loanReference() + "'s write-off reversed: it is DISBURSED again";
        }
        return ApiResult.ok(message, decided);
    }

    @Operation(summary = "Withdraw a write-off request", description = "Only whoever proposed it, before anyone"
            + " decides it. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn",
                    content = @Content(examples = @ExampleObject(StaffLoanWriteOffApiExamples.WITHDRAWN))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN, or not the"
                    + " proposer",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not the proposer",
                                    value = StaffLoanWriteOffApiExamples.NOT_THE_PROPOSER),
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN)})),
            @ApiResponse(responseCode = "404", description = "No such request",
                    content = @Content(examples = @ExampleObject(StaffLoanWriteOffApiExamples.NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already decided or withdrawn",
                    content = @Content(examples = @ExampleObject(StaffLoanWriteOffApiExamples.ALREADY_DECIDED)))
    })
    @DeleteMapping("/staff-loan-write-offs/{writeOffId}")
    @PreAuthorize(PROPOSERS)
    public ApiResult<StaffLoanWriteOffResponse> withdraw(@PathVariable Long writeOffId) {
        return ApiResult.ok("Write-off request withdrawn", writeOffService.withdraw(writeOffId));
    }

    @Operation(summary = "Write-off requests",
            description = "Newest first, each with its loan and the loan's status now. status=PENDING is the checker's"
                    + " queue; staffLoanId gives one loan's history of write-offs and reversals.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(StaffLoanWriteOffApiExamples.REQUESTS))),
            @ApiResponse(responseCode = "400", description = "An unknown status or kind",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'status'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-loan-write-offs")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffLoanWriteOffResponse>> requests(
            @Parameter(description = "Only requests in this status", example = "PENDING")
            @RequestParam(required = false) StaffLoanWriteOffStatus status,
            @Parameter(description = "Only write-offs or only reversals", example = "WRITE_OFF")
            @RequestParam(required = false) StaffLoanWriteOffKind kind,
            @Parameter(description = "Only this loan's requests", example = "151")
            @RequestParam(required = false) Long staffLoanId,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(writeOffService.requests(status, kind, staffLoanId,
                Paging.of(page, size))));
    }
}
