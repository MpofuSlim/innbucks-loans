package zw.co.reikan.loans.core.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Per-merchant lending performance over a period (by application
 * {@code createdDate}): applications received, principal applied for, and how
 * many of those loans have been successfully disbursed with the amount paid out.
 */
public record MerchantPerformanceReportResponse(
        LocalDate fromDate,
        LocalDate toDate,
        List<MerchantPerformance> merchants) {

    public record MerchantPerformance(String merchantCode,
                                      String merchantName,
                                      long applications,
                                      BigDecimal totalPrincipal,
                                      long disbursedCount,
                                      BigDecimal totalDisbursed) {
    }
}
