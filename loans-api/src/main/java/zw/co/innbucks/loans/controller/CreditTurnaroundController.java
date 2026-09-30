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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaroundReportResponse;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaroundService;
import zw.co.innbucks.loans.core.turnaround.ServiceLevelResponse;
import zw.co.innbucks.loans.core.turnaround.ServiceLevelService;
import zw.co.innbucks.loans.core.turnaround.ServiceLevelStage;
import zw.co.innbucks.loans.core.turnaround.UpdateServiceLevelRequest;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.time.LocalDate;
import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Credit turnaround", description = "How long applications wait for a credit decision, against a service"
        + " level set without a release (FR-SSB-015 / FR-PBL-030). A wait starts when SSB approves the deduction, or"
        + " again when the originator answers a return, and is measured in wall-clock hours. Past targetHours it is"
        + " overdue; past escalationHours it is escalated once: logged, audited and emailed to the administrators. Each"
        + " loan waiting on Credit carries its own creditTurnaround in the loan views.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class CreditTurnaroundController {

    private final ServiceLevelService serviceLevelService;
    private final CreditTurnaroundService creditTurnaroundService;

    @Operation(summary = "List the service levels", description = "One per measured stage; only CREDIT_DECISION for now.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.SERVICE_LEVELS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/service-levels")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<List<ServiceLevelResponse>> list() {
        return ApiResult.ok(serviceLevelService.list());
    }

    @Operation(summary = "Change a service level",
            description = "Replaces a stage's target and escalation point. It applies at once, to loans already"
                    + " waiting as well: the target is how long a decision should take, whenever the loan arrived. A"
                    + " wait already escalated stays escalated. Audited with what it was.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed",
                    content = @Content(examples = @ExampleObject(ApiExamples.SERVICE_LEVEL_UPDATED))),
            @ApiResponse(responseCode = "400", description = "A missing or out-of-range field, an escalation point"
                    + " before the target, or not a stage",
                    content = @Content(examples = {
                            @ExampleObject(name = "Out of range", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "targetHours": "Target hours must be at least 1"
                                      }
                                    }"""),
                            @ExampleObject(name = "Escalation before the target", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Escalation hours cannot be fewer than the target hours"
                                    }"""),
                            @ExampleObject(name = "Not a stage", value = """
                                    {
                                      "code": "INVALID_PARAMETER",
                                      "message": "Invalid value for 'stage'"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @PutMapping("/service-levels/{stage}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<ServiceLevelResponse> update(
            @PathVariable ServiceLevelStage stage,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.SERVICE_LEVEL_REQUEST)))
            @Valid @RequestBody UpdateServiceLevelRequest request) {
        return ApiResult.ok("Service level updated; it applies to loans already waiting as well",
                serviceLevelService.update(stage, request));
    }

    @Operation(summary = "Credit turnaround report",
            description = "Every credit decision (approval, rejection or return) made in the period, timed from when"
                    + " its loan reached Credit, against the current service level: how many were within the target"
                    + " (adherencePercent), the average, median and longest wait, and the same by kind of decision."
                    + " unmeasured counts decisions on a loan with no record of reaching Credit. awaiting, overdue and"
                    + " escalated describe the queue now, whatever the period. fromDate and toDate are market days,"
                    + " inclusive; month to date when omitted.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.CREDIT_TURNAROUND_REPORT))),
            @ApiResponse(responseCode = "400", description = "An inverted period",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_REQUEST",
                              "message": "fromDate must not be after toDate"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/reports/credit-turnaround")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
    public ApiResult<CreditTurnaroundReportResponse> report(
            @Parameter(example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ApiResult.ok(creditTurnaroundService.report(fromDate, toDate));
    }
}
