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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.voucher.VoucherCodeResponse;
import zw.co.innbucks.loans.core.voucher.VoucherDeliveryStatus;
import zw.co.innbucks.loans.core.voucher.VoucherDetailResponse;
import zw.co.innbucks.loans.core.voucher.VoucherReasonRequest;
import zw.co.innbucks.loans.core.voucher.VoucherResponse;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementService;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;
import zw.co.innbucks.loans.web.VoucherApiExamples;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Vouchers", description = "Staff Grocery Loan vouchers (FR-SGL-033 to FR-SGL-040). A loan is paid out as a"
        + " GetMore voucher, never as cash: one per disbursement, worth what was disbursed, sent to the customer by SMS"
        + " (WhatsApp when the SMS fails), spent at GetMore's tills in one go or over several purchases, until it"
        + " expires. The code is 16 digits, the last a check digit, shown as 4829 1506 7331 8406 and sent as"
        + " 4829-1506-7331-8406. Everywhere here it is masked; only a VOUCHER_SUPPORT user can see it in full, one"
        + " voucher at a time and on the record. Vouchers are issued by the disbursement, never from a screen.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class VoucherController {

    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN','VOUCHER_SUPPORT')";
    private static final String CANCELLERS = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String SENDERS = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN','VOUCHER_SUPPORT')";
    private static final String REPORT_READERS = "hasAnyRole('FINANCE','CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String REASON_MISSING = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "reason": "reason is required"
              }
            }""";

    private final VoucherService voucherService;
    private final VoucherSettlementService settlementService;

    @Operation(summary = "Vouchers",
            description = "Newest first, codes masked. status is as of now: an open voucher past its expiry is EXPIRED"
                    + " even before the expiry job marks it. deliveryStatus=FAILED is the list of vouchers no channel"
                    + " could deliver, for follow-up (FR-SGL-037). issuedFrom and issuedTo are market days.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.VOUCHERS))),
            @ApiResponse(responseCode = "400", description = "An unknown enum value, or issuedFrom after issuedTo",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'status'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, SUPER_ADMIN or VOUCHER_SUPPORT",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/vouchers")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<VoucherResponse>> vouchers(
            @Parameter(description = "Only vouchers in this status, as of now", example = "PARTIALLY_REDEEMED")
            @RequestParam(required = false) VoucherStatus status,
            @Parameter(description = "Only vouchers whose delivery stands here", example = "FAILED")
            @RequestParam(required = false) VoucherDeliveryStatus deliveryStatus,
            @Parameter(description = "Only this employee's", example = "E1012")
            @RequestParam(required = false) String employeeNumber,
            @Parameter(description = "Only this loan's", example = "SGL-2026-000143")
            @RequestParam(required = false) String loanAccount,
            @Parameter(description = "Issued on or after this market day", example = "2026-10-06")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedFrom,
            @Parameter(description = "Issued on or before this market day", example = "2026-10-08")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedTo,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(voucherService.vouchers(status, deliveryStatus, employeeNumber,
                loanAccount, issuedFrom, issuedTo, Paging.of(page, size))));
    }

    @Operation(summary = "A voucher",
            description = "With every purchase made with it (redemptions) and every attempt to send it (deliveries),"
                    + " oldest first. The code is masked.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.VOUCHER_7_DETAIL))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, SUPER_ADMIN or VOUCHER_SUPPORT",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such voucher",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.NOT_FOUND_8)))
    })
    @GetMapping("/vouchers/{voucherId}")
    @PreAuthorize(READERS)
    public ApiResult<VoucherDetailResponse> voucher(@PathVariable Long voucherId) {
        return ApiResult.ok(voucherService.voucher(voucherId));
    }

    @Operation(summary = "Show a voucher's code in full",
            description = "VOUCHER_SUPPORT only (FR-SGL-040): no other role, SUPER_ADMIN included, sees a code. Give the"
                    + " reason; it is audited with your name each time. code is for reading out, scanValue (digits"
                    + " only) for a QR code or a till.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.VOUCHER_7_CODE))),
            @ApiResponse(responseCode = "400", description = "No reason given",
                    content = @Content(examples = @ExampleObject(REASON_MISSING))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not VOUCHER_SUPPORT",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such voucher",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.NOT_FOUND_8))),
            @ApiResponse(responseCode = "503", description = "The voucher code keys are not configured",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.UNAVAILABLE)))
    })
    @PostMapping("/vouchers/{voucherId}/code-reveals")
    @PreAuthorize("hasRole('VOUCHER_SUPPORT')")
    public ApiResult<VoucherCodeResponse> reveal(@PathVariable Long voucherId,
                                                 @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
                                                 @Content(examples = @ExampleObject(VoucherApiExamples.REASON_REQUEST)))
                                                 @Valid @RequestBody VoucherReasonRequest request) {
        return ApiResult.ok(voucherService.reveal(voucherId, request.getReason()));
    }

    @Operation(summary = "Cancel a voucher",
            description = "CREDIT_MANAGER or SUPER_ADMIN. Only before anything has been spent with it, and before it"
                    + " expires; audited with the reason. What becomes of the loan and the money paid to GetMore is a"
                    + " separate decision.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.VOUCHER_8_CANCELLED))),
            @ApiResponse(responseCode = "400", description = "No reason given",
                    content = @Content(examples = @ExampleObject(REASON_MISSING))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such voucher",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.NOT_FOUND_8))),
            @ApiResponse(responseCode = "409", description = "Spent from, expired, or already closed",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "Voucher 7 has been partly spent and cannot be cancelled"
                            }""")))
    })
    @PostMapping("/vouchers/{voucherId}/cancellation")
    @PreAuthorize(CANCELLERS)
    public ApiResult<VoucherResponse> cancel(@PathVariable Long voucherId,
                                             @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
                                             @Content(examples = @ExampleObject(VoucherApiExamples.CANCEL_REQUEST)))
                                             @Valid @RequestBody VoucherReasonRequest request) {
        return ApiResult.ok("Voucher " + voucherId + " cancelled", voucherService.cancel(voucherId,
                request.getReason()));
    }

    @Operation(summary = "Send a voucher again",
            description = "CREDIT_MANAGER, SUPER_ADMIN or VOUCHER_SUPPORT. To the number it was issued to and no other,"
                    + " stating what is left on it: the follow-up to a FAILED delivery (FR-SGL-037), or a customer who"
                    + " lost the message. Answers at once; the outcome shows in the voucher's deliveries. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Queued",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.VOUCHER_8_RESENT))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, SUPER_ADMIN or VOUCHER_SUPPORT",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such voucher",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.NOT_FOUND_8))),
            @ApiResponse(responseCode = "409", description = "Nothing left to spend, or being sent right now",
                    content = @Content(examples = {
                            @ExampleObject(name = "Closed", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Voucher 8 is CANCELLED: there is nothing left to spend, so it is not sent"
                                    }"""),
                            @ExampleObject(name = "Sending", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Voucher 8 is being sent already"
                                    }""")})),
            @ApiResponse(responseCode = "503", description = "The voucher code keys are not configured",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.UNAVAILABLE)))
    })
    @PostMapping("/vouchers/{voucherId}/deliveries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(SENDERS)
    public ApiResult<VoucherResponse> resend(@PathVariable Long voucherId) {
        return new ApiResult<>("ACCEPTED", "Voucher " + voucherId + " queued to be sent again",
                voucherService.resend(voucherId));
    }

    @Operation(summary = "Daily settlement and reconciliation report",
            description = "FINANCE, CREDIT_MANAGER or SUPER_ADMIN (FR-SGL-038). One market day: vouchers issued (the"
                    + " value paid to GetMore's settlement account at disbursement), redeemed (by outlet), cancelled,"
                    + " and lapsed with something left (expiredUnredeemedValue), with totals per currency and every"
                    + " event as a line, codes masked. Today's report covers the day so far. format=csv downloads the"
                    + " lines as a spreadsheet, voucher-settlement-<date>.csv.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = {@Content(mediaType = "application/json",
                            examples = @ExampleObject(VoucherApiExamples.SETTLEMENT_REPORT)),
                            @Content(mediaType = "text/csv")}),
            @ApiResponse(responseCode = "400", description = "A day still to come, or an unknown format",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_REQUEST",
                              "message": "date (2026-12-01) is in the future"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not FINANCE, CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/voucher-settlement-reports/{date}")
    @PreAuthorize(REPORT_READERS)
    public ResponseEntity<?> settlementReport(
            @Parameter(description = "The market day", example = "2026-10-08")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "json (the default) or csv", example = "csv")
            @RequestParam(required = false) String format) {
        if (format == null || format.equalsIgnoreCase("json")) {
            return ResponseEntity.ok(ApiResult.ok(settlementService.report(date)));
        }
        if (!format.equalsIgnoreCase("csv")) {
            throw new ValidationException("format must be json or csv");
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("voucher-settlement-" + date + ".csv").build().toString())
                .body(settlementService.csv(date));
    }
}
