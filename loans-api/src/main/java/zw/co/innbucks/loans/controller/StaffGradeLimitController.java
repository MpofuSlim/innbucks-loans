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
import org.springframework.format.annotation.DateTimeFormat;
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
import zw.co.innbucks.loans.core.staff.ProposeStaffGradeLimitRequest;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitChangeResponse;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitChangeStatus;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitDecision;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitDecisionRequest;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitResponse;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.time.LocalDate;
import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff grade limits", description = "The Staff Grocery Loan grade-to-limit matrix (FR-SGL-009,"
        + " FR-SGL-010): each grade maps to the most a staff member of that grade may borrow, and a score band label,"
        + " from an effective market date. A grade is whatever the bank grades its staff by: a Paterson grade such as"
        + " C4, or a band such as MANAGER or CLERK/ASSISTANT/AGENT. It is stored upper case with runs of spaces as one"
        + " and none around a slash, so 'Clerk / Assistant / Agent' is the same grade. The matrix changes only under maker-checker: a CREDIT_MANAGER or"
        + " SUPER_ADMIN proposes a change, and another one approves or rejects it. A change applies from today or later,"
        + " never earlier, and a limit already in force is never rewritten: change it from a later day. A limit of 0"
        + " stops lending to the grade. Loans keep the amount they were booked for.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffGradeLimitController {

    private final StaffGradeLimitService staffGradeLimitService;

    @Operation(summary = "The grade-to-limit matrix on a day",
            description = "Every grade with an approved limit or a pending proposal, alphabetically. current is the"
                    + " limit in force on asOf (absent while the grade's first limit is still to come); scheduled lists"
                    + " the approved limits from later days, soonest first; pendingChanges counts the proposals waiting"
                    + " for a checker. asOf defaults to today in the market's time zone.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_GRADE_LIMITS))),
            @ApiResponse(responseCode = "400", description = "asOf is not a yyyy-MM-dd date",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'asOf'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-grade-limits")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')")
    public ApiResult<List<StaffGradeLimitResponse>> matrix(
            @Parameter(description = "The market day to show the matrix for (yyyy-MM-dd); today by default",
                    example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResult.ok(staffGradeLimitService.matrix(asOf));
    }

    @Operation(summary = "Changes to the matrix",
            description = "Newest first. status=PENDING is the checker's queue; grade gives one grade's history. A"
                    + " PENDING change carries replacing: the approved limit the grade would otherwise have on its"
                    + " effective date, absent for a grade with none.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_GRADE_LIMIT_CHANGES))),
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
    @GetMapping("/staff-grade-limit-changes")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')")
    public ApiResult<List<StaffGradeLimitChangeResponse>> changes(
            @Parameter(description = "Only this grade's changes", example = "C4")
            @RequestParam(required = false) String grade,
            @Parameter(description = "Only changes in this status", example = "PENDING")
            @RequestParam(required = false) StaffGradeLimitChangeStatus status) {
        return ApiResult.ok(staffGradeLimitService.changes(grade, status));
    }

    @Operation(summary = "Propose a grade limit",
            description = "CREDIT_MANAGER or SUPER_ADMIN. The grade's maximum loan amount and score band from"
                    + " effectiveFrom (today or later, in the market's time zone). It applies only once a different"
                    + " CREDIT_MANAGER or SUPER_ADMIN approves it. Proposing for a date whose approved limit is still to"
                    + " come replaces that limit when approved. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Proposed",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_GRADE_LIMIT_PROPOSED))),
            @ApiResponse(responseCode = "400", description = "A missing or bad field, or a date in the past",
                    content = @Content(examples = {
                            @ExampleObject(name = "Bad fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "grade": "Grade must be at most 32 letters, digits, spaces, hyphens or slashes, e.g. C4 or CLERK/ASSISTANT/AGENT",
                                        "maximumLimit": "Maximum limit must have at most 2 decimal places"
                                      }
                                    }"""),
                            @ExampleObject(name = "Date in the past", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "A grade limit cannot apply from 2026-09-30, which has passed; the earliest is today, 2026-10-01"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "A proposal for the grade and date is already waiting, or"
                    + " the grade's limit from that date is already in force",
                    content = @Content(examples = {
                            @ExampleObject(name = "Already waiting", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "A change to grade C4 from 2026-11-01 is already waiting for approval (change 2); approve, reject or withdraw it first"
                                    }"""),
                            @ExampleObject(name = "Already in force", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Grade C4's limit from 2026-10-01 is already in force and cannot be replaced; propose a change from a later day"
                                    }""")}))
    })
    @PostMapping("/staff-grade-limit-changes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
    public ApiResult<StaffGradeLimitChangeResponse> propose(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_GRADE_LIMIT_PROPOSAL)))
            @Valid @RequestBody ProposeStaffGradeLimitRequest request) {
        return new ApiResult<>("CREATED", "Grade limit change proposed; it applies once someone else approves it",
                staffGradeLimitService.propose(request));
    }

    @Operation(summary = "Approve or reject a proposed grade limit",
            description = "CREDIT_MANAGER or SUPER_ADMIN, never whoever proposed it. A rejection needs a comment. An"
                    + " approval puts the limit in the matrix from its effective date, and is refused once that date has"
                    + " passed (propose it again) or when the grade's limit from that date came into force while the"
                    + " proposal waited. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decided",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_GRADE_LIMIT_APPROVED))),
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
                                      "message": "A reason is required to reject a grade limit change"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN, or the caller proposed"
                    + " the change",
                    content = @Content(examples = {
                            @ExampleObject(name = "Own proposal", value = ApiExamples.STAFF_GRADE_LIMIT_OWN_CHANGE),
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN)})),
            @ApiResponse(responseCode = "404", description = "No such change",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_GRADE_LIMIT_CHANGE_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already decided; or its date has passed, or the grade's"
                    + " limit from that date came into force while it waited",
                    content = @Content(examples = {
                            @ExampleObject(name = "Already decided", value = ApiExamples.STAFF_GRADE_LIMIT_ALREADY_DECIDED),
                            @ExampleObject(name = "Date passed", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Grade limit change 2 was to apply from 2026-11-01, which has passed; reject it and propose it again from today or later"
                                    }""")}))
    })
    @PostMapping("/staff-grade-limit-changes/{changeId}/decision")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
    public ApiResult<StaffGradeLimitChangeResponse> decide(
            @PathVariable Long changeId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_GRADE_LIMIT_APPROVAL)))
            @Valid @RequestBody StaffGradeLimitDecisionRequest request) {
        StaffGradeLimitChangeResponse decided = staffGradeLimitService.decide(changeId, request);
        return ApiResult.ok(request.getDecision() == StaffGradeLimitDecision.APPROVED
                ? "Grade limit change approved" : "Grade limit change rejected", decided);
    }

    @Operation(summary = "Withdraw a proposed grade limit",
            description = "Only whoever proposed it, before anyone decides it. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_GRADE_LIMIT_WITHDRAWN))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN, or not the proposer",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not the proposer", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "Only credit1, who proposed grade limit change 2, can withdraw it; anyone else approves or rejects it"
                                    }"""),
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN)})),
            @ApiResponse(responseCode = "404", description = "No such change",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_GRADE_LIMIT_CHANGE_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already decided",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_GRADE_LIMIT_ALREADY_DECIDED)))
    })
    @DeleteMapping("/staff-grade-limit-changes/{changeId}")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
    public ApiResult<StaffGradeLimitChangeResponse> withdraw(@PathVariable Long changeId) {
        return ApiResult.ok("Grade limit change withdrawn", staffGradeLimitService.withdraw(changeId));
    }
}
