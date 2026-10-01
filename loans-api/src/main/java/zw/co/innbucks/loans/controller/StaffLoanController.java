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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.staff.loan.CancelStaffLoanRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanDetailResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;
import zw.co.innbucks.loans.web.StaffLoanApiExamples;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff Grocery Loans", description = "Staff Grocery Loans accepted in the SuperApp, for Credit, Finance and"
        + " Human Capital: each with the terms accepted and the agreement it was accepted under, with its evidence"
        + " (device, address, how it was authenticated). A loan awaits disbursement through the bank's system; Credit"
        + " can cancel one until then. A borrower who stops being ACTIVE on the register has theirs cancelled at once;"
        + " once it is paid out, it is flagged instead (employmentFlag) and Human Capital and Payroll, or Credit, are"
        + " emailed.")
@RestController
@RequestMapping(ApiPaths.BASE + "/staff-loans")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffLoanController {

    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')";
    private static final String CREDIT = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')";

    private final StaffLoanService loanService;

    @Operation(summary = "List Staff Grocery Loans",
            description = "CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN. Newest first. The borrower's number"
                    + " is masked; inArrears is true once a paid-out loan is past its due date and grace, or it was"
                    + " written off. employmentFlag is set on a paid-out loan whose borrower is no longer ACTIVE on"
                    + " the register: RECOVER_FROM_TERMINAL_BENEFITS when they left (RESIGNED, TERMINATED; Human"
                    + " Capital and the Payroll mailboxes were emailed), CREDIT_TO_DECIDE when they are SUSPENDED or"
                    + " on UNPAID_LEAVE (Credit was emailed). It clears when they are ACTIVE again. flagged=true lists"
                    + " only those.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = {
                    @ExampleObject(name = "All", value = StaffLoanApiExamples.STAFF_LOANS),
                    @ExampleObject(name = "flagged=true", value = StaffLoanApiExamples.STAFF_LOANS_FLAGGED)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a reader of staff loans",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffLoanResponse>> loans(
            @Parameter(description = "Only loans in this status", example = "AWAITING_DISBURSEMENT")
            @RequestParam(required = false) StaffLoanStatus status,
            @Parameter(description = "Only this employee's", example = "E1012")
            @RequestParam(required = false) String employeeNumber,
            @Parameter(description = "true: only loans flagged for a borrower no longer ACTIVE; false: only those not",
                    example = "true")
            @RequestParam(required = false) Boolean flagged,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20")
            @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(loanService.loans(status, employeeNumber, flagged,
                Paging.of(page, size))));
    }

    @Operation(summary = "A Staff Grocery Loan",
            description = "CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN. With the agreement it was accepted"
                    + " under: the exact text shown, and the evidence of the acceptance. intact is false when either"
                    + " no longer matches the seal made at acceptance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.STAFF_LOAN_DETAIL))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a reader of staff loans",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.NOT_FOUND_143)))
    })
    @GetMapping("/{staffLoanId}")
    @PreAuthorize(READERS)
    public ApiResult<StaffLoanDetailResponse> loan(@PathVariable Long staffLoanId) {
        return ApiResult.ok(loanService.loan(staffLoanId));
    }

    @Operation(summary = "Cancel a loan before payout",
            description = "CREDIT_MANAGER or SUPER_ADMIN. Stops a loan still awaiting disbursement, with the reason;"
                    + " nothing was paid, so nothing is owed. The borrower may apply again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.CANCELLED))),
            @ApiResponse(responseCode = "400", description = "No reason given",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "reason": "reason is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.NOT_FOUND_143))),
            @ApiResponse(responseCode = "409", description = "Paid out, or already closed",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.CANCEL_CONFLICT)))
    })
    @PostMapping("/{staffLoanId}/cancel")
    @PreAuthorize(CREDIT)
    public ApiResult<StaffLoanResponse> cancel(@PathVariable Long staffLoanId,
                                               @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
                                               @Content(examples = @ExampleObject(StaffLoanApiExamples.CANCEL_REQUEST)))
                                               @Valid @RequestBody CancelStaffLoanRequest request) {
        return ApiResult.ok(loanService.cancel(staffLoanId, request.reason()));
    }
}
