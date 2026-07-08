package zw.co.reikan.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.api.AgentPerformanceReportResponse;
import zw.co.reikan.loans.core.api.CommissionsReportResponse;
import zw.co.reikan.loans.core.api.DisbursementsReportResponse;
import zw.co.reikan.loans.core.api.LoanPortfolioReportResponse;
import zw.co.reikan.loans.core.api.MerchantPerformanceReportResponse;
import zw.co.reikan.loans.core.report.ReportService;

import java.time.LocalDate;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

/**
 * Admin reporting endpoints. Every report accepts an optional inclusive
 * {@code fromDate}/{@code toDate} (ISO {@code yyyy-MM-dd}); omitted bounds
 * default to month-to-date. An optional {@code merchantCode} scopes any report
 * to a single merchant; omitted means platform-wide.
 */
@Tag(name = "REPORTS")
@RestController
@RequestMapping(value = "/api/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BULKIT_ADMIN','ORGANISATION_SUPER_USER','RETAIL_SALES')")
public class ReportsController {

    private final ReportService reportService;

    @Operation(operationId = "disbursementsReport",
            summary = "DISBURSEMENTS REPORT",
            description = "Daily totals of successfully disbursed loans over the period, with grand totals.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)})
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "400", description = "Bad request, invalid date range"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Forbidden. caller lacks an admin role"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    @GetMapping("/disbursements")
    public DisbursementsReportResponse disbursements(@RequestParam(required = false) LocalDate fromDate,
                                                     @RequestParam(required = false) LocalDate toDate,
                                                     @RequestParam(required = false) String merchantCode) {
        return reportService.disbursementsReport(fromDate, toDate, merchantCode);
    }

    @Operation(operationId = "loanPortfolioReport",
            summary = "LOAN PORTFOLIO REPORT",
            description = "Loan count and principal grouped by approval status for applications received in the period.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)})
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "400", description = "Bad request, invalid date range"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Forbidden. caller lacks an admin role"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    @GetMapping("/loan-portfolio")
    public LoanPortfolioReportResponse loanPortfolio(@RequestParam(required = false) LocalDate fromDate,
                                                     @RequestParam(required = false) LocalDate toDate,
                                                     @RequestParam(required = false) String merchantCode) {
        return reportService.loanPortfolioReport(fromDate, toDate, merchantCode);
    }

    @Operation(operationId = "commissionsReport",
            summary = "COMMISSIONS REPORT",
            description = "Agent and provider commission per merchant for loans disbursed in the period.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)})
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "400", description = "Bad request, invalid date range"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Forbidden. caller lacks an admin role"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    @GetMapping("/commissions")
    public CommissionsReportResponse commissions(@RequestParam(required = false) LocalDate fromDate,
                                                 @RequestParam(required = false) LocalDate toDate,
                                                 @RequestParam(required = false) String merchantCode) {
        return reportService.commissionsReport(fromDate, toDate, merchantCode);
    }

    @Operation(operationId = "merchantPerformanceReport",
            summary = "MERCHANT PERFORMANCE REPORT",
            description = "Applications, principal, disbursed count and disbursed amount per merchant for the period.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)})
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "400", description = "Bad request, invalid date range"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Forbidden. caller lacks an admin role"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    @GetMapping("/merchant-performance")
    public MerchantPerformanceReportResponse merchantPerformance(@RequestParam(required = false) LocalDate fromDate,
                                                                 @RequestParam(required = false) LocalDate toDate,
                                                                 @RequestParam(required = false) String merchantCode) {
        return reportService.merchantPerformanceReport(fromDate, toDate, merchantCode);
    }

    @Operation(operationId = "agentPerformanceReport",
            summary = "AGENT PERFORMANCE REPORT",
            description = "Loans, disbursed amount and agent commission per capturing agent for loans disbursed in the period.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)})
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "400", description = "Bad request, invalid date range"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Forbidden. caller lacks an admin role"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    @GetMapping("/agent-performance")
    public AgentPerformanceReportResponse agentPerformance(@RequestParam(required = false) LocalDate fromDate,
                                                           @RequestParam(required = false) LocalDate toDate,
                                                           @RequestParam(required = false) String merchantCode) {
        return reportService.agentPerformanceReport(fromDate, toDate, merchantCode);
    }
}
