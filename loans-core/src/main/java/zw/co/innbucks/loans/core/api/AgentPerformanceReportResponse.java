package zw.co.innbucks.loans.core.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Per-agent sales performance over a period (successfully disbursed loans
 * only), attributed to the officer or agent who originated each loan
 * (FR-SSB-017), whichever channel it came through.
 */
public record AgentPerformanceReportResponse(
        LocalDate fromDate,
        LocalDate toDate,
        List<AgentPerformance> agents) {

    public record AgentPerformance(Long agentId,
                                   String agentUsername,
                                   String agentName,
                                   long loanCount,
                                   BigDecimal totalDisbursed,
                                   BigDecimal totalAgentCommission) {
    }
}
