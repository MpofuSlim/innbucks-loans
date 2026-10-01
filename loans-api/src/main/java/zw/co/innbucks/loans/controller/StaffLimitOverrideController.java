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
import zw.co.innbucks.loans.core.staff.offer.ProposeStaffLimitOverrideRequest;
import zw.co.innbucks.loans.core.staff.offer.RevokeStaffLimitOverrideRequest;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideDecision;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideDecisionRequest;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideResponse;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideService;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff limit overrides", description = "Credit's authorised limit for one staff member in place of their"
        + " grade's (FR-SGL-011): the only way a Staff Grocery Loan offer is made at anything but the grade limit. A"
        + " CREDIT_MANAGER or SUPER_ADMIN proposes one with a reason and another approves or rejects it, never the"
        + " proposer. It is set for the grade the member holds and applies only while they hold it. It takes effect from"
        + " the next weekly offer; a limit of 0 stops offers to the member and withdraws their open offer at once."
        + " Audited.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffLimitOverrideController {

    private static final String CREDIT = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')";

    private final StaffLimitOverrideService overrideService;

    @Operation(summary = "Propose a limit override for one staff member",
            description = "CREDIT_MANAGER or SUPER_ADMIN. The amount replaces the member's grade limit in their offers"
                    + " once another credit manager or SUPER_ADMIN approves it; 0 stops offers to them. It is set for"
                    + " the grade they hold now. One proposal per member may wait at a time. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Proposed",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDE_PROPOSED))),
            @ApiResponse(responseCode = "400", description = "A missing or invalid field",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "amount": "Amount cannot be negative",
                                "reason": "Reason is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Not on the staff register",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_MEMBER_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "The member already has a proposal waiting",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "Employee E1012 already has a limit override waiting for a decision (1); approve, reject or withdraw it first"
                            }""")))
    })
    @PostMapping("/staff-limit-overrides")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CREDIT)
    public ApiResult<StaffLimitOverrideResponse> propose(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDE_PROPOSAL)))
            @Valid @RequestBody ProposeStaffLimitOverrideRequest request) {
        return new ApiResult<>("CREATED", "Limit override proposed; it applies once another credit manager approves it",
                overrideService.propose(request));
    }

    @Operation(summary = "Approve or reject a limit override",
            description = "CREDIT_MANAGER or SUPER_ADMIN, never whoever proposed it. A rejection needs a comment."
                    + " Approval replaces the member's earlier override (SUPERSEDED) and sets their offers from the next"
                    + " weekly run; a limit of 0 also withdraws their open offer at once. Refused when the member's grade"
                    + " has changed since the proposal. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decided",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDE_APPROVED))),
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
                                      "message": "A reason is required to reject a limit override"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN, or the caller proposed"
                    + " it",
                    content = @Content(examples = {
                            @ExampleObject(name = "Own proposal", value = ApiExamples.STAFF_LIMIT_OVERRIDE_OWN),
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN)})),
            @ApiResponse(responseCode = "404", description = "No such override",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDE_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already decided or withdrawn, or the member's grade changed",
                    content = @Content(examples = {
                            @ExampleObject(name = "Already decided", value = ApiExamples.STAFF_LIMIT_OVERRIDE_ALREADY_DECIDED),
                            @ExampleObject(name = "Grade changed", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Employee E1012's grade has changed from C4 to C5 since limit override 1 was proposed; reject it and propose one for the new grade"
                                    }""")}))
    })
    @PostMapping("/staff-limit-overrides/{overrideId}/decision")
    @PreAuthorize(CREDIT)
    public ApiResult<StaffLimitOverrideResponse> decide(
            @PathVariable Long overrideId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDE_APPROVAL)))
            @Valid @RequestBody StaffLimitOverrideDecisionRequest request) {
        StaffLimitOverrideResponse decided = overrideService.decide(overrideId, request);
        return ApiResult.ok(request.getDecision() == StaffLimitOverrideDecision.APPROVED
                ? "Limit override approved; it sets the employee's offer from the next weekly run"
                : "Limit override rejected", decided);
    }

    @Operation(summary = "Withdraw a limit override", description = "Only whoever proposed it, before anyone decides"
            + " it. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "OK",
                              "message": "Limit override withdrawn",
                              "data": {
                                "id": 1,
                                "employeeNumber": "E1012",
                                "fullName": "Chipo Banda",
                                "grade": "C4",
                                "amount": 150.00,
                                "reason": "Existing salary advance outstanding until December",
                                "status": "WITHDRAWN",
                                "proposedBy": "credit1",
                                "proposedAt": "2026-10-06T09:12:30+02:00",
                                "decidedBy": "credit1",
                                "decidedAt": "2026-10-06T09:20:05+02:00"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN, or not the proposer",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not the proposer", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "Only credit1, who proposed limit override 1, can withdraw it; anyone else approves or rejects it"
                                    }"""),
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN)})),
            @ApiResponse(responseCode = "404", description = "No such override",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDE_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already decided or withdrawn",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDE_ALREADY_DECIDED)))
    })
    @DeleteMapping("/staff-limit-overrides/{overrideId}")
    @PreAuthorize(CREDIT)
    public ApiResult<StaffLimitOverrideResponse> withdraw(@PathVariable Long overrideId) {
        return ApiResult.ok("Limit override withdrawn", overrideService.withdraw(overrideId));
    }

    @Operation(summary = "Revoke an approved limit override",
            description = "CREDIT_MANAGER or SUPER_ADMIN, with a reason. The member's grade limit applies again from"
                    + " their next weekly offer. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revoked",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDE_REVOKED))),
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
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDE_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Not in force",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "Limit override 1 is revoked, so there is nothing to revoke"
                            }""")))
    })
    @PostMapping("/staff-limit-overrides/{overrideId}/revocation")
    @PreAuthorize(CREDIT)
    public ApiResult<StaffLimitOverrideResponse> revoke(
            @PathVariable Long overrideId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDE_REVOCATION)))
            @Valid @RequestBody RevokeStaffLimitOverrideRequest request) {
        return ApiResult.ok("Limit override revoked; the employee's grade limit applies from the next weekly run",
                overrideService.revoke(overrideId, request));
    }

    @Operation(summary = "Limit overrides",
            description = "Newest first. status=PENDING is the checker's queue. An APPROVED override says whether it"
                    + " is in force (inForce): it is not when the member's grade has changed since it was set, and"
                    + " notInForceReason says so.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_LIMIT_OVERRIDES))),
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
    @GetMapping("/staff-limit-overrides")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffLimitOverrideResponse>> overrides(
            @Parameter(description = "Only overrides in this status", example = "APPROVED")
            @RequestParam(required = false) StaffLimitOverrideStatus status,
            @Parameter(description = "Only this employee's overrides", example = "E1012")
            @RequestParam(required = false) String employeeNumber,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(overrideService.overrides(status, employeeNumber,
                Paging.of(page, size))));
    }
}
