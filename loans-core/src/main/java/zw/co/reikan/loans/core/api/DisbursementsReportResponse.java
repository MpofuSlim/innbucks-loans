package zw.co.reikan.loans.core.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Daily disbursements over a period (successfully disbursed loans only),
 * with grand totals for the whole range.
 */
public record DisbursementsReportResponse(
        LocalDate fromDate,
        LocalDate toDate,
        long totalCount,
        BigDecimal totalDisbursed,
        List<DailyDisbursement> days) {

    public record DailyDisbursement(LocalDate date, long count, BigDecimal totalDisbursed) {
    }
}
