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
import zw.co.innbucks.loans.core.api.AgentPerformanceReportResponse;
import zw.co.innbucks.loans.core.api.CommissionsReportResponse;
import zw.co.innbucks.loans.core.api.DisbursementsReportResponse;
import zw.co.innbucks.loans.core.api.LoanPortfolioReportResponse;
import zw.co.innbucks.loans.core.report.ReportService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.time.LocalDate;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

/**
 * Admin reports. Every report takes an optional inclusive {@code fromDate}/{@code toDate}
 * (yyyy-MM-dd, the market's calendar days); omitted bounds default to month to date. An optional
 * {@code merchantCode} scopes a report to one merchant; omitted means platform-wide.
 */
@Tag(name = "Reports", description = "SUPER_ADMIN only. Optional fromDate/toDate (yyyy-MM-dd, market days,"
        + " inclusive; month to date when omitted) and merchantCode (platform-wide when omitted).")
@RestController
@RequestMapping(ApiPaths.BASE + "/reports")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@PreAuthorize("hasRole('SUPER_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "An inverted period or an unknown merchant",
                content = @Content(examples = {
                        @ExampleObject(name = "Inverted period", value = """
                                {
                                  "code": "INVALID_REQUEST",
                                  "message": "fromDate must not be after toDate"
                                }"""),
                        @ExampleObject(name = "Unknown merchant", value = """
                                {
                                  "code": "INVALID_REQUEST",
                                  "message": "Merchant with merchant code harare-motors not found"
                                }""")})),
        @ApiResponse(responseCode = "401", description = "No valid token",
                content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
        @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
})
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "Disbursements report",
            description = "Daily totals of successfully disbursed loans over the period, with grand totals.")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "fromDate": "2026-10-01",
                "toDate": "2026-10-31",
                "totalCount": 1,
                "totalDisbursed": 500.00,
                "days": [
                  {
                    "date": "2026-10-01",
                    "count": 1,
                    "totalDisbursed": 500.00
                  }
                ]
              }
            }""")))
    @GetMapping("/disbursements")
    public ApiResult<DisbursementsReportResponse> disbursements(
            @Parameter(example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(example = "2026-10-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String merchantCode) {
        return ApiResult.ok(reportService.disbursementsReport(fromDate, toDate, merchantCode));
    }

    @Operation(summary = "Loan portfolio report",
            description = "Loan count and principal by SSB approval status for applications received in the period.")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "fromDate": "2026-09-01",
                "toDate": "2026-09-30",
                "totalLoans": 2,
                "totalPrincipal": 851.06,
                "bySsbApprovalStatus": [
                  {
                    "status": "APPROVED",
                    "count": 1,
                    "totalPrincipal": 531.91
                  },
                  {
                    "status": "PROCESSING",
                    "count": 1,
                    "totalPrincipal": 319.15
                  }
                ]
              }
            }""")))
    @GetMapping("/loan-portfolio")
    public ApiResult<LoanPortfolioReportResponse> loanPortfolio(
            @Parameter(example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String merchantCode) {
        return ApiResult.ok(reportService.loanPortfolioReport(fromDate, toDate, merchantCode));
    }

    @Operation(summary = "Commissions report",
            description = "Agent and provider commission per merchant for loans disbursed in the period.")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "fromDate": "2026-10-01",
                "toDate": "2026-10-31",
                "totalAgentCommission": 3.19,
                "totalProviderCommission": 12.77,
                "merchants": [
                  {
                    "merchantCode": "harare-motors",
                    "merchantName": "Harare Motor Spares",
                    "loanCount": 1,
                    "agentCommission": 3.19,
                    "providerCommission": 12.77
                  }
                ]
              }
            }""")))
    @GetMapping("/commissions")
    public ApiResult<CommissionsReportResponse> commissions(
            @Parameter(example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(example = "2026-10-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String merchantCode) {
        return ApiResult.ok(reportService.commissionsReport(fromDate, toDate, merchantCode));
    }

    @Operation(summary = "Agent performance report",
            description = "Loans, disbursed amount and agent commission per originating agent for loans disbursed in"
                    + " the period.")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "fromDate": "2026-10-01",
                "toDate": "2026-10-31",
                "agents": [
                  {
                    "agentId": 7,
                    "agentUsername": "tmoyo",
                    "loanCount": 1,
                    "totalDisbursed": 500.00,
                    "totalAgentCommission": 3.19
                  }
                ]
              }
            }""")))
    @GetMapping("/agent-performance")
    public ApiResult<AgentPerformanceReportResponse> agentPerformance(
            @Parameter(example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(example = "2026-10-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String merchantCode) {
        return ApiResult.ok(reportService.agentPerformanceReport(fromDate, toDate, merchantCode));
    }
}
