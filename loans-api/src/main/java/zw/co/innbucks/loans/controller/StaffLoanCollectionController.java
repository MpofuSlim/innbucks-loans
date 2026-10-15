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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.staff.loan.FinaliseCollectionRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.StaffLoanCollectionApiExamples;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff loan collections", description = "The Staff Grocery Loan payday statement: for each loan due on a"
        + " payday (day 20 of each month), what to collect from salary as its GetMore voucher stood at the cut-off,"
        + " 00:00 market time on the payday, under the treatment the loan was accepted under, and whether that amount"
        + " is final; and what to pay GetMore for the cycle (its sales less its refunds made before the cut-off and not"
        + " paid for by an earlier payday). The payday sync reads GetMore first; finalising keeps the lines as sent to"
        + " payroll and settles the GetMore rows, so one recorded later is paid in the next payday. Nothing here moves"
        + " money and no job runs it. On staging's simulated store every answer says it is not for payroll.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffLoanCollectionController {

    private static final String READERS = "hasAnyRole('FINANCE','CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String FINALISERS = "hasAnyRole('FINANCE','SUPER_ADMIN')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private final StaffLoanCollectionService collectionService;

    @Operation(summary = "A payday's collection statement",
            description = "FINANCE, CREDIT_MANAGER or SUPER_ADMIN. The payday must be a repayment day. Each loan due"
                    + " that payday has a line (written-off loans, recovered outside payroll, are in excluded with"
                    + " what they owe, as are loans whose voucher is in the other GetMore store: SIMULATED on a live"
                    + " host, LIVE on staging's simulated one): amountToCollect, and final, which is false while more"
                    + " can change it: the voucher not read from GetMore since the cut-off (STALE), a blocking sync"
                    + " issue (SYNC_BLOCKED), a sale held at the till (HELD_SALES), the voucher still open under"
                    + " REDUCED_TO_AMOUNT_SPENT, or stopped by the bank or GetMore under DEBT_STANDS (couldRiseTo"
                    + " says how far it could go); or, under either, a coupon GetMore deleted at a time loans cannot"
                    + " date (VOUCHER_STOPPED, stoppedReason STORE_WITHDRAWN on a voucher open by its dates: no sync"
                    + " saw it gone until after the cut-off), or its GetMore rows coming to below zero or above"
                    + " the repayment (ROWS_INCONSISTENT: the amount is held within those; check the rows)."
                    + " merchantSettlement is what to pay GetMore: every sale less every refund of the store's"
                    + " vouchers made before the cut-off and not paid for by an earlier payday; a row recorded after"
                    + " the previous payday was finalised is late. Until finalised it is a preview; after, the lines"
                    + " are what was sent to payroll, and a sale or refund made before the cut-off but recorded"
                    + " after the payday was finalised is in lateAdjustments. format=csv downloads the lines as"
                    + " staff-loan-collections-<payday>.csv (-SIMULATED on staging).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = {
                    @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "After the payday sync", value = StaffLoanCollectionApiExamples.REPORT),
                            @ExampleObject(name = "Before it", value = StaffLoanCollectionApiExamples.REPORT_BEFORE_SYNC),
                            @ExampleObject(name = "Finalised", value = StaffLoanCollectionApiExamples.FINALISED)}),
                    @Content(mediaType = "text/csv")}),
            @ApiResponse(responseCode = "400", description = "Not a repayment day, or an unknown format",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not a payday", value = StaffLoanCollectionApiExamples.NOT_A_PAYDAY),
                            @ExampleObject(name = "Format", value = StaffLoanCollectionApiExamples.FORMAT_INVALID)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not FINANCE, CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-loan-collections/{payday}")
    @PreAuthorize(READERS)
    public ResponseEntity<?> report(
            @Parameter(description = "The payday, a repayment day", example = "2026-11-20")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payday,
            @Parameter(description = "json (the default) or csv", example = "csv")
            @RequestParam(required = false) String format) {
        if (format == null || format.equalsIgnoreCase("json")) {
            StaffLoanCollectionReport report = collectionService.report(payday);
            return ResponseEntity.ok(report.simulated()
                    ? ApiResult.ok(StaffLoanCollectionService.NOT_FOR_PAYROLL, report) : ApiResult.ok(report));
        }
        if (!format.equalsIgnoreCase("csv")) {
            throw new ValidationException("format must be json or csv");
        }
        StaffLoanCollectionReport.Csv csv = collectionService.csv(payday);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(csv.fileName()).build().toString())
                .body(csv.content());
    }

    @Operation(summary = "Read GetMore before a payday",
            description = "FINANCE, CREDIT_MANAGER or SUPER_ADMIN. Syncs one page of the store's GetMore vouchers"
                    + " whose sales or refunds can fall into the payday's cycle (issued before its cut-off and"
                    + " expiring no earlier than the previous payday's cut-off less the sync window), whatever loan"
                    + " they belong to, by voucher id: start with after=0 and repeat with nextAfter until it is null."
                    + " The first page first withdraws what cancelled loans' voucher issuances left at GetMore, within"
                    + " the same 60 seconds (what it does not reach waits for the next first page), then syncs at"
                    + " least one voucher. A voucher GetMore could not be read for is counted unavailable and the rest"
                    + " go on, but a page stops early (stoppedEarly true, nextAfter the voucher it got to) once GetMore"
                    + " could not be read for 3 vouchers in a row, or after 60 seconds; sync again before finalising."
                    + " On staging's simulated store (simulated true) the answer says it is not for payroll.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page synced, or stopped early",
                    content = @Content(examples = {
                            @ExampleObject(name = "Synced", value = StaffLoanCollectionApiExamples.SYNCED),
                            @ExampleObject(name = "Stopped early",
                                    value = StaffLoanCollectionApiExamples.SYNC_STOPPED_EARLY)})),
            @ApiResponse(responseCode = "400", description = "Not a repayment day, or a bad page",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not a payday", value = StaffLoanCollectionApiExamples.NOT_A_PAYDAY),
                            @ExampleObject(name = "Limit", value = StaffLoanCollectionApiExamples.SYNC_LIMIT_INVALID)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not FINANCE, CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "503", description = "GetMore is OFF on this host",
                    content = @Content(examples = @ExampleObject(StaffLoanCollectionApiExamples.SYNC_OFF)))
    })
    @PostMapping("/staff-loan-collections/{payday}/getmore-syncs")
    @PreAuthorize(READERS)
    public ApiResult<StaffLoanCollectionReport.Sync> sync(
            @Parameter(description = "The payday, a repayment day", example = "2026-11-20")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payday,
            @Parameter(description = "The last voucher id of the previous page; 0 for the first", example = "0")
            @RequestParam(required = false) Long after,
            @Parameter(description = "Vouchers per page, 1 to 200", example = "50")
            @RequestParam(required = false) Integer limit) {
        StaffLoanCollectionReport.Sync sync = collectionService.syncForPayday(payday, after, limit);
        return ApiResult.ok(syncMessage(payday, sync), sync);
    }

    @Operation(summary = "Finalise a payday",
            description = "FINANCE or SUPER_ADMIN. In one step: keeps the payday's lines as they are sent to payroll"
                    + " and settles the GetMore sales and refunds its statement pays for, so one recorded later is"
                    + " paid in the next payday. Refused before the cut-off, a second time, and after a later payday"
                    + " was finalised. One payday at a time is finalised in a store; one that found the rows it was"
                    + " settling already settled by another is refused and nothing is kept. A payday with a line not"
                    + " final, or a voucher not read from GetMore since the cut-off, is refused unless acceptNonFinal"
                    + " is true with a reason (kept and audited). The body"
                    + " may be left out. On staging the simulated store's own rows are settled and the answer says it"
                    + " is not for payroll.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Finalised",
                    content = @Content(examples = @ExampleObject(StaffLoanCollectionApiExamples.FINALISED))),
            @ApiResponse(responseCode = "400", description = "Not a repayment day; acceptNonFinal with no reason, or"
                    + " a reason too long", content = @Content(examples = {
                    @ExampleObject(name = "Not a payday", value = StaffLoanCollectionApiExamples.NOT_A_PAYDAY),
                    @ExampleObject(name = "No reason", value = StaffLoanCollectionApiExamples.REASON_MISSING),
                    @ExampleObject(name = "Reason too long", value = StaffLoanCollectionApiExamples.REASON_TOO_LONG)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "Already finalised, before the cut-off, after a later"
                    + " payday, not final, or its rows settled by another finalisation while it ran",
                    content = @Content(examples = {
                    @ExampleObject(name = "Already finalised", value = StaffLoanCollectionApiExamples.ALREADY_FINALISED),
                    @ExampleObject(name = "Before the cut-off",
                            value = StaffLoanCollectionApiExamples.CUTOFF_NOT_PASSED),
                    @ExampleObject(name = "A later payday finalised",
                            value = StaffLoanCollectionApiExamples.LATER_FINALISED),
                    @ExampleObject(name = "Not final", value = StaffLoanCollectionApiExamples.NOT_FINAL),
                    @ExampleObject(name = "Settled by another finalisation",
                            value = StaffLoanCollectionApiExamples.SETTLED_CONCURRENTLY)}))
    })
    @PostMapping("/staff-loan-collections/{payday}/finalisation")
    @PreAuthorize(FINALISERS)
    public ApiResult<StaffLoanCollectionReport> finalise(
            @Parameter(description = "The payday, a repayment day", example = "2026-11-20")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payday,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = false, content = @Content(examples = {
                    @ExampleObject(name = "Everything final", value = StaffLoanCollectionApiExamples.FINALISE_REQUEST),
                    @ExampleObject(name = "Accepting what is not final",
                            value = StaffLoanCollectionApiExamples.FINALISE_ACCEPTING_REQUEST)}))
            @Valid @RequestBody(required = false) FinaliseCollectionRequest request) {
        StaffLoanCollectionReport report = collectionService.finalise(payday, request);
        return ApiResult.ok(StaffLoanCollectionService.finalisedMessage(report), report);
    }

    /**
     * What one page of the payday sync did, in words, and whether there is another; on staging's simulated store,
     * first that it is not for payroll.
     */
    static String syncMessage(LocalDate payday, StaffLoanCollectionReport.Sync sync) {
        return (sync.simulated() ? StaffLoanCollectionService.NOT_FOR_PAYROLL + ". " : "") + "Payday "
                + DAY.format(payday) + ": " + sync.synced() + " synced, " + sync.unchanged() + " unchanged, "
                + sync.blocked() + " blocked, " + sync.unavailable() + " unavailable; " + sync.cleanedUp()
                + " cancelled voucher " + (sync.cleanedUp() == 1 ? "issuance" : "issuances") + " cleaned up; "
                + (sync.stoppedEarly() ? "stopped early, GetMore is slow or not answering: repeat with after="
                + sync.nextAfter() + " once it answers"
                : sync.nextAfter() == null ? "done" : "more to sync: repeat with after=" + sync.nextAfter());
    }
}
