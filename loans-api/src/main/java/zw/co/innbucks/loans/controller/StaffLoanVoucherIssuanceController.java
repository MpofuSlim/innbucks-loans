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
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceResult;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceStatus;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.GetMoreApiExamples;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;
import zw.co.innbucks.loans.web.StaffLoanApiExamples;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff Grocery Loan voucher issuance", description = "Issuing a Staff Grocery Loan's GetMore voucher. An"
        + " accepted loan for a GetMore store is queued for its voucher with the loan and tried at once; when GetMore"
        + " is slow or unavailable it is tried again on the borrower's next read. An issuance GetMore refused, or one"
        + " that found something on its coupon prefix loans could not take as its own, is PARKED for Credit, who"
        + " tries it again here. No job runs any of this.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffLoanVoucherIssuanceController {

    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')";
    private static final String CREDIT = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')";

    private final StaffLoanVoucherIssuer issuer;

    @Operation(summary = "Voucher issuances",
            description = "CREDIT_MANAGER, FINANCE or SUPER_ADMIN. Newest first, optionally of one status: PENDING"
                    + " (due an attempt), CALLING (an attempt is calling GetMore), PARKED (stopped for Credit:"
                    + " lastOutcome says why), CANCELLING (its loan was cancelled; what it left at GetMore is being"
                    + " withdrawn), ABANDONED (withdrawn) or ISSUED. mintedCodes is how many codes GetMore returned"
                    + " that loans could not keep and holds sealed for withdrawal: a count, never a code.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = {
                    @ExampleObject(name = "Issued", value = GetMoreApiExamples.ISSUANCES),
                    @ExampleObject(name = "status=PARKED", value = GetMoreApiExamples.ISSUANCES_PARKED)})),
            @ApiResponse(responseCode = "400", description = "An unknown status",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'status'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-loan-voucher-issuances")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffLoanVoucherIssuanceResponse>> issuances(
            @Parameter(description = "Only issuances in this status", example = "PARKED")
            @RequestParam(required = false) StaffLoanVoucherIssuanceStatus status,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20")
            @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(issuer.issuances(status, Paging.of(page, size))));
    }

    @Operation(summary = "Try a loan's GetMore voucher again",
            description = "CREDIT_MANAGER or SUPER_ADMIN, with the reason (audited). For a loan awaiting its voucher:"
                    + " makes the issuance of one accepted while GetMore was OFF, puts a PARKED one back to PENDING,"
                    + " and tries it within 30 seconds; a cancelled loan's issuance (cancelling, or left pending or"
                    + " parked by an attempt under way when the loan was cancelled) is cleaned up instead: whatever it"
                    + " left at GetMore is withdrawn. For a loan paid out as its voucher whose issuance still keeps"
                    + " other codes GetMore made (mintedCodes above 0), those are withdrawn."
                    + " generateAgain=true is Credit stating that GetMore holds no usable coupon on the loan's prefix,"
                    + " after GetMore confirmed it: accepted only after GENERATED_NOT_FOUND, PREFIX_NOT_HONOURED or"
                    + " ORPHAN_LIVE, it lets one more coupon be made. A loan parked PAST_DUE is cancelled instead. The"
                    + " message says where the issuance stands after the attempt.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tried",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.RETRIED))),
            @ApiResponse(responseCode = "400", description = "No reason given",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.RETRY_REASON_MISSING))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.NOT_FOUND_143))),
            @ApiResponse(responseCode = "409", description = "Not awaiting its voucher, past its payday cut-off,"
                    + " generateAgain not applicable, or an attempt is calling GetMore right now",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not awaiting", value = GetMoreApiExamples.RETRY_NOT_AWAITING),
                            @ExampleObject(name = "Past due", value = GetMoreApiExamples.RETRY_PAST_DUE),
                            @ExampleObject(name = "Calling GetMore",
                                    value = GetMoreApiExamples.ISSUANCE_IN_PROGRESS)})),
            @ApiResponse(responseCode = "503", description = "GetMore is OFF on this host",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.GETMORE_OFF)))
    })
    @PostMapping("/staff-loans/{staffLoanId}/voucher-issuance")
    @PreAuthorize(CREDIT)
    public ApiResult<StaffLoanVoucherIssuanceResult> retry(
            @PathVariable Long staffLoanId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(GetMoreApiExamples.RETRY_REQUEST)))
            @Valid @RequestBody StaffLoanVoucherIssuanceRequest request) {
        StaffLoanVoucherIssuanceResult result = issuer.retry(staffLoanId, request.reason(),
                request.generateAgainAsked());
        return ApiResult.ok(message(result.issuance()), result);
    }

    /** Where the issuance stands after the attempt, in words. */
    static String message(StaffLoanVoucherIssuanceResponse issuance) {
        String voucherOf = "The voucher of " + issuance.loanReference();
        return switch (issuance.status()) {
            case ISSUED -> voucherOf + " is issued" + (issuance.mintedCodes() == 0 ? "" : "; "
                    + issuance.mintedCodes() + " other code(s) GetMore made are still to be withdrawn, tried again on"
                    + " the borrower's next read, or here");
            case PENDING, CALLING -> voucherOf + " is not issued yet (" + issuance.lastOutcome() + "); it is tried"
                    + " again on the borrower's next read, or here";
            case PARKED -> voucherOf + " is parked (" + issuance.lastOutcome() + ")";
            case CANCELLING -> voucherOf + " is being withdrawn at GetMore (" + issuance.lastOutcome() + ")";
            case ABANDONED -> voucherOf + " is withdrawn at GetMore: the loan was cancelled";
        };
    }
}
