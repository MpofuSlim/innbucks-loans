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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaroundReportResponse;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaroundService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.time.LocalDate;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Credit turnaround", description = "How long applications wait for a credit decision, against the"
        + " CREDIT_DECISION stage's service level (FR-SSB-015 / FR-PBL-030; set with PUT /workflow-stages/CREDIT_DECISION)."
        + " A wait starts when SSB approves the deduction, or again when the originator answers a return, and is measured"
        + " in wall-clock hours. Each loan waiting on Credit carries its own creditTurnaround in the loan views.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class CreditTurnaroundController {

    private final CreditTurnaroundService creditTurnaroundService;

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
