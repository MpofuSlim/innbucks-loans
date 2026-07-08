package zw.co.reikan.loans.core.report;

import zw.co.reikan.loans.core.api.AgentPerformanceReportResponse;
import zw.co.reikan.loans.core.api.CommissionsReportResponse;
import zw.co.reikan.loans.core.api.DisbursementsReportResponse;
import zw.co.reikan.loans.core.api.LoanPortfolioReportResponse;
import zw.co.reikan.loans.core.api.MerchantPerformanceReportResponse;

import java.time.LocalDate;

/**
 * Admin reporting over the loan book. All reports take an inclusive date range;
 * a null bound defaults to the first day of the current month / today.
 */
public interface ReportService {

    DisbursementsReportResponse disbursementsReport(LocalDate fromDate, LocalDate toDate);

    LoanPortfolioReportResponse loanPortfolioReport(LocalDate fromDate, LocalDate toDate);

    CommissionsReportResponse commissionsReport(LocalDate fromDate, LocalDate toDate);

    MerchantPerformanceReportResponse merchantPerformanceReport(LocalDate fromDate, LocalDate toDate);

    AgentPerformanceReportResponse agentPerformanceReport(LocalDate fromDate, LocalDate toDate);
}
