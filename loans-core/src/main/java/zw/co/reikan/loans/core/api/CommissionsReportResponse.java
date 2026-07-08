package zw.co.reikan.loans.core.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Agent/provider commission earned per merchant over a period (successfully
 * disbursed loans only), with range totals.
 */
public record CommissionsReportResponse(
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal totalAgentCommission,
        BigDecimal totalProviderCommission,
        List<MerchantCommission> merchants) {

    public record MerchantCommission(String merchantCode,
                                     String merchantName,
                                     long loanCount,
                                     BigDecimal agentCommission,
                                     BigDecimal providerCommission) {
    }
}
