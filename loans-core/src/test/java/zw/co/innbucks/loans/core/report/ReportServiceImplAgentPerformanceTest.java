package zw.co.innbucks.loans.core.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.api.AgentPerformanceReportResponse.AgentPerformance;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The agent performance report names each originating officer or agent, not just their username (FR-SSB-017). */
class ReportServiceImplAgentPerformanceTest {

    @Test
    @DisplayName("each row carries the agent's name from their first and last name, or none when neither is on file")
    void rowsNameTheAgent() {
        LoanRepository loans = mock(LoanRepository.class);
        when(loans.agentPerformance(any(), any(), isNull())).thenReturn(List.of(
                new Object[]{7L, "tmoyo", "Tendai", "Moyo", 3L, new BigDecimal("1500.00"), new BigDecimal("9.57")},
                new Object[]{12L, "superapp-service", null, null, 1L, new BigDecimal("300.00"), BigDecimal.ZERO}));
        ReportServiceImpl reports = new ReportServiceImpl(loans, mock(MerchantRepository.class), new MarketTimeZone("ZW"));

        List<AgentPerformance> agents = reports.agentPerformanceReport(LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), null).agents();

        assertThat(agents).extracting(AgentPerformance::agentUsername, AgentPerformance::agentName,
                        AgentPerformance::loanCount)
                .containsExactly(
                        tuple("tmoyo", "Tendai Moyo", 3L),
                        tuple("superapp-service", null, 1L));
        assertThat(agents.get(0).totalDisbursed()).isEqualByComparingTo("1500.00");
        assertThat(agents.get(0).totalAgentCommission()).isEqualByComparingTo("9.57");
    }
}
