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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliationRequest;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliationResponse;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliationService;
import zw.co.innbucks.loans.core.staff.StaffRegisterVarianceKind;
import zw.co.innbucks.loans.core.staff.StaffRegisterVarianceResponse;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff register reconciliation", description = "Reconciling the Staff Grocery Loan's staff register"
        + " against the HR payroll master (FR-SGL-008), which Human Capital does each month. A HUMAN_CAPITAL user or"
        + " SUPER_ADMIN uploads the payroll master; it is compared with the register by employee number and the"
        + " variances are kept as a report: staff the register has as employed whom the payroll says have left or does"
        + " not list (with those who can still borrow first), staff whose details differ, staff on the payroll but not"
        + " on the register (probable joiners), employee numbers on the payroll twice, and rows with no usable employee"
        + " number. It changes nothing on the register; corrections go through the register's maker-checker.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffRegisterReconciliationController {

    private static final String RUNNERS = "hasAnyRole('HUMAN_CAPITAL','SUPER_ADMIN')";
    private static final String READERS = "hasAnyRole('HUMAN_CAPITAL','CREDIT_MANAGER','FINANCE','SUPER_ADMIN')";

    private final StaffRegisterReconciliationService reconciliationService;

    @Operation(summary = "Reconcile the register against the payroll master",
            description = "HUMAN_CAPITAL or SUPER_ADMIN. The payroll master as CSV, base64-encoded in content: a header"
                    + " row, then one employee per row, at most 2 MB and 5000 rows, read like a register file (comma,"
                    + " semicolon or tab; UTF-8 or Excel's plain CSV; columns matched by name). Only an employee number"
                    + " column is required. Every other register column it has is compared (comparedFields): full name"
                    + " (or first name and surname), national ID, mobile number, grade, department, employment status,"
                    + " engagement date, wallet or account number; other columns are ignored and listed in"
                    + " ignoredColumns. Values are normalised by the register's rules before comparing; names match"
                    + " whatever their case and word order, departments whatever their case. A blank cell or a value the"
                    + " register's rules refuse is reported as a difference with a note. Someone the register has as"
                    + " employed whom the payroll's status says has left is LEFT_ON_PAYROLL, counted in leftOnPayroll"
                    + " and, if they can still borrow, leftOnPayrollEligible. Someone on the payroll whose status says"
                    + " they left, and who is not on the register, is not reported. Without a status"
                    + " column, someone on the payroll who is RESIGNED or TERMINATED on the register is reported as a"
                    + " difference. Nothing on the register changes. The report is kept, a clean one too. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reconciled; the variances are in the report",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_RECONCILED))),
            @ApiResponse(responseCode = "400", description = "A missing field, or a file that cannot be read, has no"
                    + " employee number column, or has no row with a usable employee number",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "content": "File content is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "No employee number column", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "The file has no column for employeeNumber. The columns it needs are: employee number"
                                    }"""),
                            @ExampleObject(name = "No usable employee number", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "No row of payroll-master-2026-10.csv has a usable employee number, so nothing was reconciled"
                                    }"""),
                            @ExampleObject(name = "Not base64", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "The file content is not base64"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @PostMapping("/staff-register/reconciliations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(RUNNERS)
    public ApiResult<StaffRegisterReconciliationResponse> reconcile(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_RECONCILIATION_REQUEST)))
            @Valid @RequestBody StaffRegisterReconciliationRequest request) {
        StaffRegisterReconciliationResponse reconciled = reconciliationService.reconcile(request);
        String message = reconciled.variances() == 0 ? "Payroll master reconciled: the register matches it"
                : String.format("Payroll master reconciled: %d variance%s", reconciled.variances(),
                reconciled.variances() == 1 ? "" : "s");
        return new ApiResult<>("CREATED", message, reconciled);
    }

    @Operation(summary = "Reconciliations", description = "Newest first: the first is the latest month's.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_RECONCILIATIONS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL, CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-register/reconciliations")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffRegisterReconciliationResponse>> reconciliations(
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(reconciliationService.reconciliations(Paging.of(page, size))));
    }

    @Operation(summary = "One reconciliation")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_RECONCILIATION_1))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL, CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such reconciliation",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_RECONCILIATION_NOT_FOUND)))
    })
    @GetMapping("/staff-register/reconciliations/{reconciliationId}")
    @PreAuthorize(READERS)
    public ApiResult<StaffRegisterReconciliationResponse> reconciliation(@PathVariable Long reconciliationId) {
        return ApiResult.ok(reconciliationService.reconciliation(reconciliationId));
    }

    @Operation(summary = "A reconciliation's variance report",
            description = "Most urgent first: LEFT_ON_PAYROLL (the payroll's status says they left; the fields that"
                    + " differ) and NOT_ON_PAYROLL (their register record), each with those who could still borrow"
                    + " first (eligible), then DIFFERENT (each field that disagrees; a note when the payroll's value is"
                    + " blank or breaks the register's rules), NOT_ON_REGISTER (the payroll row as sent),"
                    + " DUPLICATE_ON_PAYROLL (the rows) and UNREADABLE (the row as sent, and why). kind narrows it to"
                    + " one. Row numbers count the header as row 1.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_RECONCILIATION_VARIANCES))),
            @ApiResponse(responseCode = "400", description = "An unknown kind",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'kind'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL, CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such reconciliation",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_RECONCILIATION_NOT_FOUND)))
    })
    @GetMapping("/staff-register/reconciliations/{reconciliationId}/variances")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffRegisterVarianceResponse>> variances(
            @PathVariable Long reconciliationId,
            @Parameter(description = "Only variances of this kind", example = "NOT_ON_PAYROLL")
            @RequestParam(required = false) StaffRegisterVarianceKind kind,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(reconciliationService.variances(reconciliationId, kind,
                Paging.of(page, size))));
    }
}
