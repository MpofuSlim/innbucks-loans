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
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncTrigger;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherSync;
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
import zw.co.innbucks.loans.web.GetMoreApiExamples;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;
import zw.co.innbucks.loans.web.VoucherApiExamples;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Vouchers", description = "Staff Grocery Loan vouchers (FR-SGL-033 to FR-SGL-040). A loan is paid out as a"
        + " voucher of the merchant it was accepted for (GET /staff-loan-merchant), never as cash: one per loan, worth"
        + " what was lent, sent to the customer by WhatsApp (SMS when WhatsApp fails), spent at that merchant only, in"
        + " one go or over several purchases, until it expires. Loans' own code (codeSource LOANS) is 16 digits, the"
        + " last a check digit, shown as 4829 1506 7331 8406 and sent as 4829-1506-7331-8406, spent at the merchant's"
        + " tills through loans. A GetMore Groceries voucher (codeSource GETMORE) is a coupon GetMore's store issued"
        + " when the loan was accepted, e.g. SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C, shown and sent exactly as made, spent at"
        + " GetMore, its spends and refunds read back from GetMore by a sync (redemptions, refunds, heldAmount)."
        + " Everywhere here a code is masked; only a VOUCHER_SUPPORT user can see it in full, one voucher at a time and on the"
        + " record. Vouchers are issued by the payout, never from a screen.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class VoucherController {

    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN','VOUCHER_SUPPORT')";
    private static final String CANCELLERS = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String SENDERS = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN','VOUCHER_SUPPORT')";
    private static final String REPORT_READERS = "hasAnyRole('FINANCE','CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String SYNCERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN','VOUCHER_SUPPORT')";
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
    private final GetMoreVoucherSync getMoreSync;

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
                    + " reason; it is audited with your name each time. code is for reading out, scanValue for a QR"
                    + " code or a till: loans' own code grouped to read and digits only to scan, a GetMore code"
                    + " exactly as GetMore made it in both.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = {
                    @ExampleObject(name = "Loans' own code", value = VoucherApiExamples.VOUCHER_7_CODE),
                    @ExampleObject(name = "A GetMore code", value = GetMoreApiExamples.VOUCHER_9_CODE)})),
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
                    + " expires; audited with the reason. What becomes of the loan and the money paid to the merchant"
                    + " is a separate decision. A GetMore voucher is cancelled only with its loan"
                    + " (POST /staff-loans/{id}/cancel), never on its own.")
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
            @ApiResponse(responseCode = "409", description = "Spent from, expired, already closed, or a GetMore"
                    + " voucher", content = @Content(examples = {
                    @ExampleObject(name = "Spent from", value = """
                            {
                              "code": "CONFLICT",
                              "message": "Voucher 7 has been partly spent and cannot be cancelled"
                            }"""),
                    @ExampleObject(name = "A GetMore voucher", value = GetMoreApiExamples.VOUCHER_CANCEL_GETMORE)}))
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

    @Operation(summary = "Sync a GetMore voucher with GetMore",
            description = "CREDIT_MANAGER, FINANCE, SUPER_ADMIN or VOUCHER_SUPPORT. Reads the voucher's sales (orders)"
                    + " and refunds (credit memos) back from GetMore now and records what is new: one redemption per"
                    + " sale per invoiced amount, one refund per credit memo, what is held at the till (ordered, not"
                    + " yet invoiced), and any problem as a sync issue (a blocking one stops the voucher's ledger"
                    + " there: outcome BLOCKED). A lapsed voucher's code is withdrawn at GetMore. Also what to do"
                    + " before writing off a loan whose lapsed GetMore coupon is still live. Codes are never shown.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Read and recorded",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SYNCED))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, SUPER_ADMIN or"
                    + " VOUCHER_SUPPORT", content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such voucher",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.NOT_FOUND_8))),
            @ApiResponse(responseCode = "409", description = "Not a GetMore voucher of this host's store mode",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SYNC_NOT_GETMORE))),
            @ApiResponse(responseCode = "503", description = "GetMore could not be read, or another sync of the"
                    + " voucher is running: try again", content = @Content(examples = {
                    @ExampleObject(name = "Unreadable", value = GetMoreApiExamples.SYNC_UNAVAILABLE),
                    @ExampleObject(name = "Busy", value = GetMoreApiExamples.SYNC_BUSY),
                    @ExampleObject(name = "Vouchers off", value = VoucherApiExamples.UNAVAILABLE)}))
    })
    @PostMapping("/vouchers/{voucherId}/getmore-syncs")
    @PreAuthorize(SYNCERS)
    public ApiResult<GetMoreSyncResult> sync(@PathVariable Long voucherId) {
        GetMoreSyncResult result = getMoreSync.sync(voucherId, GetMoreSyncTrigger.OPERATOR);
        return switch (result.outcome()) {
            case SYNCED -> ApiResult.ok("Voucher " + voucherId + " synced with GetMore", result);
            case UNCHANGED -> ApiResult.ok("Voucher " + voucherId + " is up to date with GetMore", result);
            case BLOCKED -> ApiResult.ok("Voucher " + voucherId + " synced up to a blocking issue ("
                    + (result.voucher() == null ? "see its sync issues" : result.voucher().syncBlockedBy()) + ")",
                    result);
            case SKIPPED, MODE_MISMATCH -> throw new ConflictException("Voucher " + voucherId
                    + " is not a GetMore voucher of this host's store mode");
            case UNAVAILABLE -> throw new GetMoreUnavailableException(null, GetMoreUnavailableException.Reason
                    .READ_FAILED, "Cannot read voucher " + voucherId + " at GetMore right now; try again.");
            case BUSY, STALE_SNAPSHOT, CONFLICT_RETRY -> throw new GetMoreUnavailableException(null,
                    GetMoreUnavailableException.Reason.BUSY, "Another sync of voucher " + voucherId
                    + " is running; try again.");
        };
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
                    + " credit extended as each voucher; nothing is paid to the merchant until payday), redeemed (by"
                    + " outlet), refunded (a GetMore sale given back), cancelled, and lapsed with something left"
                    + " (expiredUnredeemedValue), with totals per currency (redeemedValue every purchase,"
                    + " netRedeemedValue less refunds), the same per merchant (byMerchant: what each merchant is"
                    + " settled on), and every event as a line, codes masked. A GetMore sale or refund is on the day"
                    + " loans read it back from GetMore, with GetMore's own time as occurredAt, so a past day never"
                    + " changes; a simulated GetMore voucher is reported only on a host whose store is SIMULATED,"
                    + " and the report then says simulated: true. merchantCode limits it to one merchant. Today's"
                    + " report covers the day so far. format=csv downloads the lines as a spreadsheet,"
                    + " voucher-settlement-<date>.csv, or voucher-settlement-<date>-<merchantCode>.csv for one"
                    + " merchant.")
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
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No merchant has that merchantCode",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "Merchant pick-n-pay not found"
                            }""")))
    })
    @GetMapping("/voucher-settlement-reports/{date}")
    @PreAuthorize(REPORT_READERS)
    public ResponseEntity<?> settlementReport(
            @Parameter(description = "The market day", example = "2026-10-08")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "json (the default) or csv", example = "csv")
            @RequestParam(required = false) String format,
            @Parameter(description = "One merchant only; every merchant when absent", example = "getmore-groceries")
            @RequestParam(required = false) String merchantCode) {
        if (format == null || format.equalsIgnoreCase("json")) {
            return ResponseEntity.ok(ApiResult.ok(settlementService.report(date, merchantCode)));
        }
        if (!format.equalsIgnoreCase("csv")) {
            throw new ValidationException("format must be json or csv");
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(csvName(date, merchantCode)).build().toString())
                .body(settlementService.csv(date, merchantCode));
    }

    /** The day in the name, and the merchant when the report is one merchant's, letters, digits and hyphens only. */
    private static String csvName(LocalDate date, String merchantCode) {
        String merchant = merchantCode == null ? "" : merchantCode.strip().replaceAll("[^A-Za-z0-9-]", "");
        return "voucher-settlement-" + date + (merchant.isEmpty() ? "" : "-" + merchant) + ".csv";
    }
}
