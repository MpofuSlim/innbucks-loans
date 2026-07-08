package zw.co.reikan.loans.core.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Loan portfolio over a period (by application {@code createdDate}): loan count
 * and principal grouped by approval status, plus range totals.
 */
public record LoanPortfolioReportResponse(
        LocalDate fromDate,
        LocalDate toDate,
        long totalLoans,
        BigDecimal totalPrincipal,
        List<StatusBreakdown> byApprovalStatus) {

    public record StatusBreakdown(String status, long count, BigDecimal totalPrincipal) {
    }
}
