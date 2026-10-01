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
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMemberChangeResponse;
import zw.co.innbucks.loans.core.staff.StaffMemberResponse;
import zw.co.innbucks.loans.core.staff.StaffRecordRequest;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchResponse;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchStatus;
import zw.co.innbucks.loans.core.staff.StaffRegisterDecision;
import zw.co.innbucks.loans.core.staff.StaffRegisterDecisionRequest;
import zw.co.innbucks.loans.core.staff.StaffRegisterRowOutcome;
import zw.co.innbucks.loans.core.staff.StaffRegisterRowResponse;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.StaffRegisterUploadRequest;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff register", description = "The Staff Grocery Loan's staff register (FR-SGL-001 to FR-SGL-007), kept"
        + " by Human Capital under maker-checker. A HUMAN_CAPITAL user or SUPER_ADMIN submits a file or one record's"
        + " change as a batch; a different one approves or rejects it, and nothing reaches the register before then."
        + " A file's rows that break the rules are refused with their reasons and the rest are submitted. Every field a"
        + " batch changes is recorded with its previous and new value, the submitter, the approver and the time. Only"
        + " ACTIVE staff whose grade has a limit above zero today are eligible to borrow.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffRegisterController {

    private static final String MAKERS = "hasAnyRole('HUMAN_CAPITAL','SUPER_ADMIN')";
    private static final String READERS = "hasAnyRole('HUMAN_CAPITAL','CREDIT_MANAGER','FINANCE','SUPER_ADMIN')";

    private final StaffRegisterService staffRegisterService;

    @Operation(summary = "Submit a staff register file",
            description = "HUMAN_CAPITAL or SUPER_ADMIN. A CSV file, base64-encoded in content: a header row, then one"
                    + " staff member per row, at most 2 MB and 5000 rows. Comma, semicolon or tab separators; UTF-8 or"
                    + " Excel's plain CSV. Columns are matched by name, ignoring case and punctuation: employee number,"
                    + " full name (or first name and surname), national ID, mobile number, grade, department,"
                    + " employment status, engagement date (yyyy-MM-dd or dd/MM/yyyy), and wallet or account number."
                    + " Other columns are ignored and listed in ignoredColumns. A row is refused when a field is"
                    + " missing or malformed, its grade is not in the grade-to-limit matrix, its employee number or"
                    + " mobile number appears twice in the file, or its mobile number belongs to another employee."
                    + " Refused rows come back in rejected; the rest are submitted as one PENDING batch, keyed by"
                    + " employee number (a new one adds a staff member, a known one replaces their record). Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Submitted",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_REGISTER_UPLOADED))),
            @ApiResponse(responseCode = "400", description = "A missing field, a file that cannot be read, or one"
                    + " whose every row was refused",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "content": "File content is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Missing columns", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "The file has no column for grade, department. The columns it needs are: employee number, full name (or first name and surname), national ID, mobile number, grade, department, employment status, engagement date, wallet or account number"
                                    }"""),
                            @ExampleObject(name = "Not base64", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "The file content is not base64"
                                    }"""),
                            @ExampleObject(name = "Every row refused",
                                    value = ApiExamples.STAFF_REGISTER_NOTHING_TO_LOAD)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @PostMapping("/staff-register/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MAKERS)
    public ApiResult<StaffRegisterBatchResponse> upload(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_REGISTER_UPLOAD_REQUEST)))
            @Valid @RequestBody StaffRegisterUploadRequest request) {
        return new ApiResult<>("CREATED", "Staff register file submitted; it reaches the register once someone else"
                + " approves it", staffRegisterService.upload(request));
    }

    @Operation(summary = "Submit one staff record",
            description = "HUMAN_CAPITAL or SUPER_ADMIN. The whole record, checked by the same rules as a row of a"
                    + " file: a new employee number adds a staff member, a known one replaces their record (to mark"
                    + " someone RESIGNED, send their record with the new status). Submitted as a one-row PENDING batch"
                    + " for someone else to approve. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Submitted",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_RECORD_SUBMITTED))),
            @ApiResponse(responseCode = "400", description = "The record breaks the register's rules, or would change"
                    + " nothing",
                    content = @Content(examples = {
                            @ExampleObject(name = "Bad fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "grade": "Grade C9 is not in the grade-to-limit matrix",
                                        "mobileNumber": "Mobile number 263782606983 already belongs to employee E1001"
                                      }
                                    }"""),
                            @ExampleObject(name = "Nothing to change", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Employee E1043's record already holds exactly these values; there is nothing to change"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @PostMapping("/staff-register/changes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MAKERS)
    public ApiResult<StaffRegisterBatchResponse> submit(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_RECORD_REQUEST)))
            @Valid @RequestBody StaffRecordRequest request) {
        return new ApiResult<>("CREATED", "Staff record change submitted; it reaches the register once someone else"
                + " approves it", staffRegisterService.submit(request));
    }

    @Operation(summary = "Staff register batches",
            description = "Newest first. status=PENDING is the checker's queue.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_REGISTER_BATCHES))),
            @ApiResponse(responseCode = "400", description = "An unknown status",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'status'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL, CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-register/batches")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffRegisterBatchResponse>> batches(
            @Parameter(description = "Only batches in this status", example = "PENDING")
            @RequestParam(required = false) StaffRegisterBatchStatus status,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(staffRegisterService.batches(status, Paging.of(page, size))));
    }

    @Operation(summary = "One staff register batch")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_REGISTER_BATCH_12))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL, CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such batch",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_BATCH_NOT_FOUND)))
    })
    @GetMapping("/staff-register/batches/{batchId}")
    @PreAuthorize(READERS)
    public ApiResult<StaffRegisterBatchResponse> batch(@PathVariable Long batchId) {
        return ApiResult.ok(staffRegisterService.batch(batchId));
    }

    @Operation(summary = "A batch's rows",
            description = "In file order; outcome narrows them (REJECTED for the refusal report, STAGED for what the"
                    + " checker releases). A STAGED row's changes show what approving it would change in the register"
                    + " as it stands now: every field for a new staff member, only the differing ones for an existing"
                    + " one, none when nothing would change. A REJECTED or SKIPPED row's errors say why.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_REGISTER_STAGED_ROWS))),
            @ApiResponse(responseCode = "400", description = "An unknown outcome",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'outcome'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL, CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such batch",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_BATCH_NOT_FOUND)))
    })
    @GetMapping("/staff-register/batches/{batchId}/rows")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffRegisterRowResponse>> rows(
            @PathVariable Long batchId,
            @Parameter(description = "Only rows with this outcome", example = "STAGED")
            @RequestParam(required = false) StaffRegisterRowOutcome outcome,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(staffRegisterService.rows(batchId, outcome, Paging.of(page, size))));
    }

    @Operation(summary = "Approve or reject a staff register batch",
            description = "HUMAN_CAPITAL or SUPER_ADMIN, never whoever submitted it. A rejection needs a comment. An"
                    + " approval applies the staged rows in file order, each checked again against the register as it"
                    + " stands: a row whose mobile number another approved batch has since given someone else is"
                    + " SKIPPED with its reason, and the rest are applied. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decided",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_REGISTER_APPROVED))),
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
                                      "message": "A reason is required to reject a staff register batch"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL or SUPER_ADMIN, or the caller submitted"
                    + " the batch",
                    content = @Content(examples = {
                            @ExampleObject(name = "Own batch", value = ApiExamples.STAFF_BATCH_OWN),
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN)})),
            @ApiResponse(responseCode = "404", description = "No such batch",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_BATCH_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already decided or withdrawn",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_BATCH_ALREADY_DECIDED)))
    })
    @PostMapping("/staff-register/batches/{batchId}/decision")
    @PreAuthorize(MAKERS)
    public ApiResult<StaffRegisterBatchResponse> decide(
            @PathVariable Long batchId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_REGISTER_APPROVAL)))
            @Valid @RequestBody StaffRegisterDecisionRequest request) {
        StaffRegisterBatchResponse decided = staffRegisterService.decide(batchId, request);
        String message = request.getDecision() == StaffRegisterDecision.APPROVED
                ? String.format("Staff register batch approved: %d added, %d changed, %d unchanged, %d skipped",
                decided.createdRows(), decided.amendedRows(), decided.unchangedRows(), decided.skippedRows())
                : "Staff register batch rejected";
        return ApiResult.ok(message, decided);
    }

    @Operation(summary = "Withdraw a staff register batch",
            description = "Only whoever submitted it, before anyone decides it. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "OK",
                              "message": "Staff register batch withdrawn",
                              "data": {
                                "id": 13,
                                "source": "MANUAL",
                                "status": "WITHDRAWN",
                                "submittedBy": "hc1",
                                "submittedAt": "2026-10-01T14:30:12+02:00",
                                "comment": "Resigned with effect from 30 September",
                                "totalRows": 1,
                                "stagedRows": 1,
                                "rejectedRows": 0,
                                "decidedBy": "hc1",
                                "decidedAt": "2026-10-01T14:41:09+02:00"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL or SUPER_ADMIN, or not the submitter",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not the submitter", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "Only hc1, who submitted staff register batch 13, can withdraw it; anyone else approves or rejects it"
                                    }"""),
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN)})),
            @ApiResponse(responseCode = "404", description = "No such batch",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_BATCH_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already decided or withdrawn",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_BATCH_ALREADY_DECIDED)))
    })
    @DeleteMapping("/staff-register/batches/{batchId}")
    @PreAuthorize(MAKERS)
    public ApiResult<StaffRegisterBatchResponse> withdraw(@PathVariable Long batchId) {
        return ApiResult.ok("Staff register batch withdrawn", staffRegisterService.withdraw(batchId));
    }

    @Operation(summary = "The staff register",
            description = "By employee number, each member with whether they may borrow today (eligible): ACTIVE and"
                    + " a grade whose limit in force today is above zero. When not, ineligibleReason says why. Filters:"
                    + " status, grade, department (contains) and q (employee number, name, or at least 4 digits of the"
                    + " mobile number).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_MEMBERS))),
            @ApiResponse(responseCode = "400", description = "An unknown status",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'status'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL, CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-members")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffMemberResponse>> members(
            @Parameter(description = "Employment status", example = "ACTIVE")
            @RequestParam(required = false) StaffEmploymentStatus status,
            @Parameter(description = "Grade", example = "C4") @RequestParam(required = false) String grade,
            @Parameter(description = "Department contains", example = "fin")
            @RequestParam(required = false) String department,
            @Parameter(description = "Employee number, name, or at least 4 digits of the mobile number",
                    example = "moyo")
            @RequestParam(required = false) String q,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(staffRegisterService.members(status, grade, department, q,
                Paging.of(page, size))));
    }

    @Operation(summary = "One staff member", description = "With whether they may borrow today.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_MEMBER_E1043))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL, CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Not on the register",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_MEMBER_NOT_FOUND)))
    })
    @GetMapping("/staff-members/{employeeNumber}")
    @PreAuthorize(READERS)
    public ApiResult<StaffMemberResponse> member(@PathVariable String employeeNumber) {
        return ApiResult.ok(staffRegisterService.member(employeeNumber));
    }

    @Operation(summary = "A staff member's change history",
            description = "Every field ever changed, newest first (FR-SGL-006): previous value (absent when the record"
                    + " was created), new value, the batch, who submitted it, who approved it, and when. A grade renamed"
                    + " in the matrix shows as a grade change with gradeChangeId instead of batchId.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_MEMBER_E1043_HISTORY))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL, CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Not on the register",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_MEMBER_NOT_FOUND)))
    })
    @GetMapping("/staff-members/{employeeNumber}/history")
    @PreAuthorize(READERS)
    public ApiResult<List<StaffMemberChangeResponse>> history(@PathVariable String employeeNumber) {
        return ApiResult.ok(staffRegisterService.history(employeeNumber));
    }
}
