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
import zw.co.innbucks.loans.core.staff.loan.ProposeStaffArrearsOverrideRequest;
import zw.co.innbucks.loans.core.staff.loan.RevokeStaffArrearsOverrideRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffArrearsOverrideDecision;
import zw.co.innbucks.loans.core.staff.loan.StaffArrearsOverrideDecisionRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffArrearsOverrideResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffArrearsOverrideService;
import zw.co.innbucks.loans.core.staff.offer.StaffArrearsOverrideStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;
import zw.co.innbucks.loans.web.StaffArrearsOverrideApiExamples;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff arrears overrides", description = "Credit's override of the arrears rule for one staff member"
        + " (FR-SGL-014): with it, a written-off Staff Grocery Loan no longer stops them borrowing. It never lifts an"
        + " overdue loan, which is still open and must be repaid before another (FR-SGL-013). A CREDIT_MANAGER or"
        + " SUPER_ADMIN proposes one with a reason and a last day, at most 90 days ahead; another approves or rejects"
        + " it, never the proposer. While it is in force the member is offered and may apply like anyone else; the loan"
        + " they accept uses it up (USED). Audited.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffArrearsOverrideController {

    private static final String CREDIT = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')";

    private final StaffArrearsOverrideService overrideService;

    @Operation(summary = "Propose an arrears override for one staff member",
            description = "CREDIT_MANAGER or SUPER_ADMIN. Only for a member who owes a written-off Staff Grocery Loan."
                    + " Once another credit manager or SUPER_ADMIN approves it, the member may take one loan under it"
                    + " up to validUntil (today to 90 days ahead). One proposal per member may wait at a time."
                    + " Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Proposed",
                    content = @Content(examples = @ExampleObject(StaffArrearsOverrideApiExamples.PROPOSED))),
            @ApiResponse(responseCode = "400", description = "A missing field, or a last day before today or more than"
                    + " 90 days ahead",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "validUntil": "Valid until is required",
                                        "reason": "Reason is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Too far ahead",
                                    value = StaffArrearsOverrideApiExamples.VALID_UNTIL_TOO_FAR)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Not on the staff register",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_MEMBER_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "The member owes no written-off loan, or already has a"
                    + " proposal waiting",
                    content = @Content(examples = {
                            @ExampleObject(name = "Nothing to override",
                                    value = StaffArrearsOverrideApiExamples.NOTHING_TO_OVERRIDE),
                            @ExampleObject(name = "Proposal waiting",
                                    value = StaffArrearsOverrideApiExamples.PENDING_EXISTS)}))
    })
    @PostMapping("/staff-arrears-overrides")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CREDIT)
    public ApiResult<StaffArrearsOverrideResponse> propose(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(StaffArrearsOverrideApiExamples.PROPOSAL)))
            @Valid @RequestBody ProposeStaffArrearsOverrideRequest request) {
        return new ApiResult<>("CREATED", "Arrears override proposed; it applies once another credit manager approves"
                + " it", overrideService.propose(request));
    }

    @Operation(summary = "Approve or reject an arrears override",
            description = "CREDIT_MANAGER or SUPER_ADMIN, never whoever proposed it. A rejection needs a comment."
                    + " Approval replaces the member's earlier approved override (SUPERSEDED); from then on they are"
                    + " offered at the next weekly run and may apply in the SuperApp at once. Refused once its last day"
                    + " has passed, or if the member no longer owes a written-off loan. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decided",
                    content = @Content(examples = @ExampleObject(StaffArrearsOverrideApiExamples.APPROVED))),
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
                                      "message": "A reason is required to reject an arrears override"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN, or the caller proposed"
                    + " it",
                    content = @Content(examples = {
                            @ExampleObject(name = "Own proposal", value = StaffArrearsOverrideApiExamples.OWN),
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN)})),
            @ApiResponse(responseCode = "404", description = "No such override",
                    content = @Content(examples = @ExampleObject(StaffArrearsOverrideApiExamples.NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already decided or withdrawn, past its last day, or the"
                    + " member owes no written-off loan any more",
                    content = @Content(examples = {
                            @ExampleObject(name = "Already decided",
                                    value = StaffArrearsOverrideApiExamples.ALREADY_DECIDED),
                            @ExampleObject(name = "Past its last day", value = StaffArrearsOverrideApiExamples.LAPSED)}))
    })
    @PostMapping("/staff-arrears-overrides/{overrideId}/decision")
    @PreAuthorize(CREDIT)
    public ApiResult<StaffArrearsOverrideResponse> decide(
            @PathVariable Long overrideId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(StaffArrearsOverrideApiExamples.APPROVAL)))
            @Valid @RequestBody StaffArrearsOverrideDecisionRequest request) {
        StaffArrearsOverrideResponse decided = overrideService.decide(overrideId, request);
        return ApiResult.ok(request.getDecision() == StaffArrearsOverrideDecision.APPROVED
                ? "Arrears override approved; the employee may take one Staff Grocery Loan under it until "
                + decided.validUntil()
                : "Arrears override rejected", decided);
    }

    @Operation(summary = "Withdraw an arrears override", description = "Only whoever proposed it, before anyone decides"
            + " it. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn",
                    content = @Content(examples = @ExampleObject(StaffArrearsOverrideApiExamples.WITHDRAWN))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN, or not the proposer",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not the proposer",
                                    value = StaffArrearsOverrideApiExamples.NOT_THE_PROPOSER),
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN)})),
            @ApiResponse(responseCode = "404", description = "No such override",
                    content = @Content(examples = @ExampleObject(StaffArrearsOverrideApiExamples.NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already decided or withdrawn",
                    content = @Content(examples = @ExampleObject(StaffArrearsOverrideApiExamples.ALREADY_DECIDED)))
    })
    @DeleteMapping("/staff-arrears-overrides/{overrideId}")
    @PreAuthorize(CREDIT)
    public ApiResult<StaffArrearsOverrideResponse> withdraw(@PathVariable Long overrideId) {
        return ApiResult.ok("Arrears override withdrawn", overrideService.withdraw(overrideId));
    }

    @Operation(summary = "Revoke an approved arrears override",
            description = "CREDIT_MANAGER or SUPER_ADMIN, with a reason, before the member borrows under it. The"
                    + " written-off balance stops new loans again: an offer they still hold can no longer be accepted,"
                    + " and the next weekly run withdraws it. A USED override cannot be revoked. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revoked",
                    content = @Content(examples = @ExampleObject(StaffArrearsOverrideApiExamples.REVOKED))),
            @ApiResponse(responseCode = "400", description = "No reason",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "reason": "Reason is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such override",
                    content = @Content(examples = @ExampleObject(StaffArrearsOverrideApiExamples.NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Not APPROVED: never in force, or already used",
                    content = @Content(examples = @ExampleObject(StaffArrearsOverrideApiExamples.NOTHING_TO_REVOKE)))
    })
    @PostMapping("/staff-arrears-overrides/{overrideId}/revocation")
    @PreAuthorize(CREDIT)
    public ApiResult<StaffArrearsOverrideResponse> revoke(
            @PathVariable Long overrideId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(StaffArrearsOverrideApiExamples.REVOCATION)))
            @Valid @RequestBody RevokeStaffArrearsOverrideRequest request) {
        return ApiResult.ok("Arrears override revoked; the written-off balance stops new loans again",
                overrideService.revoke(overrideId, request));
    }

    @Operation(summary = "Arrears overrides",
            description = "Newest first. status=PENDING is the checker's queue. An APPROVED override says whether it"
                    + " is in force (inForce): it is not once its last day has passed, and notInForceReason says so. A"
                    + " USED override names the loan taken under it (staffLoanReference).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(StaffArrearsOverrideApiExamples.OVERRIDES))),
            @ApiResponse(responseCode = "400", description = "An unknown status",
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
    @GetMapping("/staff-arrears-overrides")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffArrearsOverrideResponse>> overrides(
            @Parameter(description = "Only overrides in this status", example = "APPROVED")
            @RequestParam(required = false) StaffArrearsOverrideStatus status,
            @Parameter(description = "Only this employee's overrides", example = "E1060")
            @RequestParam(required = false) String employeeNumber,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(overrideService.overrides(status, employeeNumber,
                Paging.of(page, size))));
    }
}
