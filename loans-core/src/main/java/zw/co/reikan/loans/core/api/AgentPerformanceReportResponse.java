package zw.co.reikan.loans.core.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Per-agent sales performance over a period (successfully disbursed loans
 * only, attributed to the user that captured the loan).
 */
public record AgentPerformanceReportResponse(
        LocalDate fromDate,
        LocalDate toDate,
        List<AgentPerformance> agents) {

    public record AgentPerformance(Long agentId,
                                   String agentUsername,
                                   long loanCount,
                                   BigDecimal totalDisbursed,
                                   BigDecimal totalAgentCommission) {
    }
}
