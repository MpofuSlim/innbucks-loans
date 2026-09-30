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
import zw.co.innbucks.loans.core.workflow.CheckpointDecisionRequest;
import zw.co.innbucks.loans.core.workflow.CheckpointDecisionResponse;
import zw.co.innbucks.loans.core.workflow.CheckpointOutcome;
import zw.co.innbucks.loans.core.workflow.CheckpointService;
import zw.co.innbucks.loans.core.workflow.LoanCheckpointResponse;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Checkpoints", description = "Clearing or declining loans held at checkpoint stages (FR-SSB-014): the"
        + " built-in PAYOUT_AUTHORISATION (FR-SSB-018), where a loan Credit has approved waits for its payout to be"
        + " authorised once an administrator switches it on, and any an administrator has added (see POST"
        + " /workflow-stages). A checkpoint's queue is GET /work-queues/{stage}/items, and its items are assigned like"
        + " any other stage's.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@Slf4j
public class CheckpointController {

    private final CheckpointService checkpointService;

    @Operation(summary = "Clear or decline a loan at a checkpoint",
            description = "Whoever may work the checkpoint (see GET /workflow-stages) records how the loan leaves it,"
                    + " once, with a comment. CLEARED lets it carry on: it is lodged, can be approved, or is booked"
                    + " and paid on the next run, unless something else holds it. DECLINED declines the application"
                    + " as a credit rejection: it needs an active REJECTED reason code, goes in the credit decision"
                    + " log, flags an SSB deduction already lodged for cancellation, and the applicant is told by SMS"
                    + " (the comment is not sent). At PAYOUT_AUTHORISATION, CLEARED authorises the payout. Nobody may"
                    + " decide a loan they originated or are a party to, nor, at a checkpoint after Credit's approval"
                    + " (BEFORE_BOOKING, such as PAYOUT_AUTHORISATION), a loan they approved. If the checkpoint is"
                    + " EXCLUSIVE and the loan's item is assigned, only its assignee may decide it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded",
                    content = @Content(examples = {
                            @ExampleObject(name = "Cleared", value = ApiExamples.CHECKPOINT_CLEARED),
                            @ExampleObject(name = "Declined", value = ApiExamples.CHECKPOINT_DECLINED)})),
            @ApiResponse(responseCode = "400", description = "No outcome or comment, or a decline without a fitting"
                    + " reason code",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "comment": "Comment is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "No reason code", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "A reason code is required to decline"
                                    }"""),
                            @ExampleObject(name = "Reason code for another decision", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Reason code APPROVE_WITHIN_POLICY is for APPROVED decisions, not a decline"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not entitled to work the checkpoint, or the caller"
                    + " originated the loan, is a party to it, or approved it at a checkpoint after the approval",
                    content = @Content(examples = {
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN),
                            @ExampleObject(name = "Originator", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "Loan 000000061 was originated by tmoyo, who cannot also decide its Payout authorisation; someone else must"
                                    }"""),
                            @ExampleObject(name = "Approver", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "Loan 000000061 was approved by admin, who cannot also decide its Payout authorisation; someone else must"
                                    }""")})),
            @ApiResponse(responseCode = "404", description = "No such checkpoint (or a system stage), or no such loan",
                    content = @Content(examples = {
                            @ExampleObject(name = "Checkpoint", value = ApiExamples.CHECKPOINT_NOT_FOUND),
                            @ExampleObject(name = "Loan", value = ApiExamples.LOAN_NOT_FOUND)})),
            @ApiResponse(responseCode = "409", description = "The checkpoint is not holding the loan (not at its"
                    + " point, not a loan it applies to, already decided, or inactive), or the checkpoint is EXCLUSIVE"
                    + " and the loan's item is assigned to someone else",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not held there", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan 000000061 is not waiting at Payout authorisation"
                                    }"""),
                            @ExampleObject(name = "Assigned to someone else", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan 000000061's Payout authorisation is assigned to finance2; only they can act on it until it is released or reassigned"
                                    }""")}))
    })
    @PostMapping("/loans/{loanId}/checkpoints/{stage}")
    @PreAuthorize("isAuthenticated() and @workflowAccess.may(authentication, #stage, 'WORK')")
    public ApiResult<CheckpointDecisionResponse> decide(
            @PathVariable Long loanId,
            @PathVariable String stage,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
                    @ExampleObject(name = "Clear", value = ApiExamples.CHECKPOINT_CLEAR_REQUEST),
                    @ExampleObject(name = "Decline", value = ApiExamples.CHECKPOINT_DECLINE_REQUEST)}))
            @Valid @RequestBody CheckpointDecisionRequest request) {
        log.info("Checkpoint {} {} on loan {}", stage, request.getOutcome(), loanId);
        CheckpointDecisionResponse decided = checkpointService.decide(stage, loanId, request);
        return ApiResult.ok(request.getOutcome() == CheckpointOutcome.CLEARED ? "Cleared; the loan carries on"
                : "The application is declined", decided);
    }

    @Operation(summary = "A loan's checkpoints",
            description = "The checkpoints holding the loan now (PENDING, with when its wait began and who has it),"
                    + " then how it left every checkpoint it passed (CLEARED or DECLINED), oldest first. Empty when no"
                    + " checkpoint has held it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_61_CHECKPOINTS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @GetMapping("/loans/{loanId}/checkpoints")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<List<LoanCheckpointResponse>> checkpoints(@PathVariable Long loanId) {
        return ApiResult.ok(checkpointService.forLoan(loanId));
    }
}
