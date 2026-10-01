package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferResponse;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunResponse;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunService;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunStatus;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunner;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferScheduleResponse;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferService;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff offers", description = "The Staff Grocery Loan's weekly Offer Generation run (FR-SGL-015 to"
        + " FR-SGL-018, FR-SGL-024). Each week every eligible member of the staff register (ACTIVE, grade limit above"
        + " zero) gets a pre-approved offer at their grade's limit, open for a configurable number of days, replacing"
        + " any offer they still hold. Staff with an active loan or arrears under this product are left out, and so are"
        + " those the latest payroll reconciliation lists as having left. A run goes ahead only when the register was"
        + " reconciled against the payroll master recently enough. A member gets at most one offer per week, so running"
        + " again only tops up those still without one.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffOfferController {

    private static final String RUNNERS = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')";

    private final StaffOfferRunner runner;
    private final StaffOfferRunService runService;
    private final StaffOfferService offerService;

    @Operation(summary = "Run this week's offer generation now",
            description = "CREDIT_MANAGER or SUPER_ADMIN. The same run the weekly schedule starts, for the current"
                    + " market week. It is refused (409 RUN_REFUSED, recorded) when the staff register's latest payroll"
                    + " reconciliation is older than the configured limit, or there has been none. Otherwise: open"
                    + " offers past their expiry are closed; every ACTIVE member whose grade has a limit above zero"
                    + " today, without an active loan or arrears under this product and not listed by the latest"
                    + " reconciliation as having left, gets an offer at that limit unless they already hold this"
                    + " week's; and any open offer whose holder no longer qualifies is withdrawn. A member gets at most"
                    + " one offer per week, so running again only offers those still without one. All or nothing: a"
                    + " run that fails keeps nothing and is recorded as FAILED. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Completed; the counts say what it did",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_OFFER_RUN_STARTED))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "Refused because the register is not reconciled recently"
                    + " enough (the attempt is recorded), or another run is in progress",
                    content = @Content(examples = {
                            @ExampleObject(name = "Reconciliation too old", value = ApiExamples.STAFF_OFFER_RUN_REFUSED),
                            @ExampleObject(name = "Never reconciled", value = """
                                    {
                                      "code": "RUN_REFUSED",
                                      "message": "The staff register has never been reconciled against the payroll master; Human Capital must run a reconciliation before offers can be generated",
                                      "data": {
                                        "id": 1,
                                        "cycleStart": "2026-10-05",
                                        "trigger": "MANUAL",
                                        "startedBy": "credit1",
                                        "startedAt": "2026-10-05T07:45:10+02:00",
                                        "finishedAt": "2026-10-05T07:45:10+02:00",
                                        "status": "REFUSED",
                                        "reason": "The staff register has never been reconciled against the payroll master; Human Capital must run a reconciliation before offers can be generated"
                                      }
                                    }"""),
                            @ExampleObject(name = "Run in progress", value = ApiExamples.STAFF_OFFER_RUN_IN_PROGRESS)}))
    })
    @PostMapping("/staff-offer-runs")
    @PreAuthorize(RUNNERS)
    public ResponseEntity<ApiResult<StaffOfferRunResponse>> run() {
        StaffOfferRunResponse run = runner.runNow();
        if (run.status() == StaffOfferRunStatus.REFUSED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResult.error("RUN_REFUSED", run.reason(), run));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResult<>("CREATED", String.format(
                "Offer run completed: %d offered, %d refreshed, %d already held this week's offer", run.offered(),
                run.refreshed(), run.alreadyOffered()), run));
    }

    @Operation(summary = "Offer runs", description = "Every attempt, scheduled or manual, newest first, with its"
            + " outcome: COMPLETED with its counts, REFUSED or FAILED with the reason.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_OFFER_RUNS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-offer-runs")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffOfferRunResponse>> runs(
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(runService.runs(Paging.of(page, size))));
    }

    @Operation(summary = "One offer run")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_OFFER_RUN_1_RESULT))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such run",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_OFFER_RUN_NOT_FOUND)))
    })
    @GetMapping("/staff-offer-runs/{runId}")
    @PreAuthorize(READERS)
    public ApiResult<StaffOfferRunResponse> run(@PathVariable Long runId) {
        return ApiResult.ok(runService.run(runId));
    }

    @Operation(summary = "The weekly run's schedule and readiness",
            description = "The schedule (a cron read on the market's clock; it fires only where the scheduled-tasks"
                    + " profile is on) and when it next fires, how long offers stay open, how recent the payroll"
                    + " reconciliation must be, the latest reconciliation and its age in days, whether a run now would"
                    + " go ahead (readyToRun, and notReadyReason when not), and the latest run.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_OFFER_SCHEDULE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-offer-schedule")
    @PreAuthorize(READERS)
    public ApiResult<StaffOfferScheduleResponse> schedule() {
        return ApiResult.ok(runService.schedule());
    }

    @Operation(summary = "Pre-approved offers",
            description = "Newest first. status: ACTIVE (open), EXPIRED (lapsed unaccepted; an open offer past its"
                    + " expiry already reads as EXPIRED), SUPERSEDED (replaced by a later week's offer while open) or"
                    + " WITHDRAWN (its holder stopped qualifying; closedReason says why). Filter by employeeNumber or"
                    + " runId. An offer keeps the grade, score band and amount it was issued with.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_OFFERS))),
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
    @GetMapping("/staff-offers")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffOfferResponse>> offers(
            @Parameter(description = "Only offers in this status", example = "ACTIVE")
            @RequestParam(required = false) StaffOfferStatus status,
            @Parameter(description = "Only this employee's offers", example = "E1001")
            @RequestParam(required = false) String employeeNumber,
            @Parameter(description = "Only offers issued by this run", example = "1")
            @RequestParam(required = false) Long runId,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(offerService.offers(status, employeeNumber, runId,
                Paging.of(page, size))));
    }
}
