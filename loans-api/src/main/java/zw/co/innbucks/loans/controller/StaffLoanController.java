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
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCancellation;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanDetailResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.GetMoreApiExamples;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;
import zw.co.innbucks.loans.web.StaffLoanApiExamples;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff Grocery Loans", description = "Staff Grocery Loans accepted in the SuperApp, for Credit, Finance and"
        + " Human Capital: each with the terms accepted and the agreement it was accepted under, with its evidence"
        + " (device, address, how it was authenticated). A loan awaits disbursement through the bank's system, or,"
        + " for a GetMore store, is paid out as its GetMore voucher when it is accepted; Credit can cancel one until"
        + " then, or while its GetMore voucher is unused. A borrower who stops being ACTIVE on the register has theirs"
        + " cancelled at once; once it is paid out, it is flagged instead (employmentFlag) and Human Capital and Payroll, or Credit, are"
        + " emailed.")
@RestController
@RequestMapping(ApiPaths.BASE + "/staff-loans")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffLoanController {

    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')";
    private static final String CREDIT = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')";

    private final StaffLoanService loanService;
    private final StaffLoanCancellation cancellation;

    @Operation(summary = "List Staff Grocery Loans",
            description = "CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN. Newest first. The borrower's number"
                    + " is masked; inArrears is true once a paid-out loan is past its due date and grace, or it was"
                    + " written off. employmentFlag is set on a paid-out loan whose borrower is no longer ACTIVE on"
                    + " the register: RECOVER_FROM_TERMINAL_BENEFITS when they left (RESIGNED, TERMINATED; Human"
                    + " Capital and the Payroll mailboxes were emailed), CREDIT_TO_DECIDE when they are SUSPENDED or"
                    + " on UNPAID_LEAVE (Credit was emailed). It clears when they are ACTIVE again. flagged=true lists"
                    + " only those. arrearsOverrideId is set on a loan accepted under Credit's arrears override"
                    + " (FR-SGL-014), when the borrower owed a written-off loan.")
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

    @Operation(summary = "Cancel a loan",
            description = "CREDIT_MANAGER or SUPER_ADMIN, with the reason. A loan still awaiting disbursement is"
                    + " cancelled; if its GetMore voucher was being issued, whatever GetMore made for it is withdrawn"
                    + " (409 VOUCHER_ISSUANCE_IN_PROGRESS while an attempt is calling GetMore: try again in a minute)."
                    + " A loan paid out as a GetMore voucher is cancelled only if the voucher was never used: GetMore"
                    + " is read first (nothing spent, refunded or held at the till, no open sync issue), the coupon is"
                    + " withdrawn, GetMore is read again, and loan and voucher are CANCELLED; nothing is owed. A spend"
                    + " that raced in, or a write-off, leaves the loan standing with its voucher stopped (409). When"
                    + " GetMore cannot be read or refuses (503, 502 GETMORE_REJECTED), nothing changed in loans unless"
                    + " the message says the coupon is withdrawn; when the withdrawal's answer was lost (502"
                    + " GETMORE_OUTCOME_UNKNOWN) the voucher is stopped, its coupon perhaps gone, and the loan stands."
                    + " Repeating the request is safe in every case. The message says what became of the coupon,"
                    + " including one of a cancelled issuance used at GetMore after the cancel"
                    + " (COUPON_USED_AFTER_CANCEL, parked for Credit). The borrower may apply again once it is"
                    + " cancelled.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled", content = @Content(examples = {
                    @ExampleObject(name = "Awaiting payout", value = StaffLoanApiExamples.CANCELLED),
                    @ExampleObject(name = "Paid out as an unused GetMore voucher",
                            value = GetMoreApiExamples.CANCELLED_WITHDRAWN)})),
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
            @ApiResponse(responseCode = "409", description = "Closed already, its voucher used, a sync issue open,"
                    + " a spend raced in, or its voucher being issued right now", content = @Content(examples = {
                    @ExampleObject(name = "Closed", value = StaffLoanApiExamples.CANCEL_CONFLICT),
                    @ExampleObject(name = "Voucher used", value = GetMoreApiExamples.CANCEL_USED),
                    @ExampleObject(name = "Sync issue open", value = GetMoreApiExamples.CANCEL_SYNC_ISSUE),
                    @ExampleObject(name = "A spend raced in", value = GetMoreApiExamples.CANCEL_RACED),
                    @ExampleObject(name = "Voucher being issued", value = GetMoreApiExamples.ISSUANCE_IN_PROGRESS)})),
            @ApiResponse(responseCode = "502", description = "GetMore refused to withdraw the coupon, or its answer"
                    + " was lost", content = @Content(examples = {
                    @ExampleObject(name = "Refused", value = GetMoreApiExamples.CANCEL_REJECTED),
                    @ExampleObject(name = "Answer lost", value = GetMoreApiExamples.CANCEL_OUTCOME_UNKNOWN)})),
            @ApiResponse(responseCode = "503", description = "GetMore could not be read",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.CANCEL_UNAVAILABLE)))
    })
    @PostMapping("/{staffLoanId}/cancel")
    @PreAuthorize(CREDIT)
    public ApiResult<StaffLoanResponse> cancel(@PathVariable Long staffLoanId,
                                               @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
                                               @Content(examples = @ExampleObject(StaffLoanApiExamples.CANCEL_REQUEST)))
                                               @Valid @RequestBody CancelStaffLoanRequest request) {
        StaffLoanCancellation.Result result = cancellation.cancel(staffLoanId, request.reason());
        return ApiResult.ok(result.message(), result.loan());
    }
}
