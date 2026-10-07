package zw.co.innbucks.loans.core.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.innbucks.loans.core.api.DashboardStatsResponse;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanBatchRepository;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.FAILED;
import static zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.PENDING;
import static zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.SUCCESS;

/**
 * The dashboard's figures folded from the grouped rows, worked by hand, and its one-entry cache. That the folded
 * figures equal what the five whole-table queries returned is pinned on real Postgres by
 * {@code DashboardAndSearchPostgresIT}.
 */
class DashboardServiceImplTest {

    private static final DashboardEntityCounts COUNTS = new DashboardEntityCounts(6, 23, 31);

    @Test
    void everyFigureIsFoldedFromTheGroups() {
        List<DashboardLoanGroup> groups = List.of(
                new DashboardLoanGroup(LoanApprovalStatus.NEW, InternalApprovalStatus.PENDING, PENDING, 4,
                        new BigDecimal("1800.00"), null),
                // Two groups of APPROVED loans awaiting Credit: both count.
                new DashboardLoanGroup(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING, PENDING, 2,
                        new BigDecimal("900.00"), null),
                new DashboardLoanGroup(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING, null, 1, null,
                        null),
                // Paid: the only groups whose sums reach the totals.
                new DashboardLoanGroup(LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED, SUCCESS, 3,
                        new BigDecimal("1350.00"), new BigDecimal("13.50")),
                new DashboardLoanGroup(LoanApprovalStatus.PAID, InternalApprovalStatus.APPROVED, SUCCESS, 1,
                        new BigDecimal("450.25"), null),
                new DashboardLoanGroup(LoanApprovalStatus.PROCESSING, InternalApprovalStatus.APPROVED, FAILED, 2,
                        new BigDecimal("900.00"), new BigDecimal("9.00")),
                // No SSB status at all: in the total only.
                new DashboardLoanGroup(null, null, null, 5, null, null));

        DashboardStatsResponse stats = DashboardServiceImpl.fold(groups, COUNTS);

        assertThat(stats.getTotalLoans()).isEqualTo(18);
        assertThat(stats.getLoansBySsbApprovalStatus()).containsExactly(
                entry("NEW", 4), entry("PROCESSING", 2), entry("APPROVED", 6), entry("REJECTED", 0), entry("PAID", 1),
                entry("FAILED", 0));
        assertThat(stats.getPendingCreditApprovals()).isEqualTo(3);
        assertThat(stats.getLoansByDisbursementStatus()).containsExactly(
                entry("PENDING", 6), entry("FAILED", 2), entry("SUCCESS", 4));
        assertThat(stats.getTotalDisbursedAmount()).isEqualTo(new BigDecimal("1800.25"));
        assertThat(stats.getTotalAgentCommission()).isEqualTo(new BigDecimal("13.50"));
        assertThat(stats.getMerchantCount()).isEqualTo(6);
        assertThat(stats.getUserCount()).isEqualTo(23);
        assertThat(stats.getBatchCount()).isEqualTo(31);
    }

    @Test
    void noLoans_everyTileIsZero_andTheSumsAreAPlainZero() {
        DashboardStatsResponse stats = DashboardServiceImpl.fold(List.of(), COUNTS);

        assertThat(stats.getTotalLoans()).isZero();
        assertThat(stats.getLoansBySsbApprovalStatus()).hasSize(6).allSatisfy((status, count) -> assertThat(count)
                .isZero());
        assertThat(stats.getLoansByDisbursementStatus()).hasSize(3).allSatisfy((status, count) -> assertThat(count)
                .isZero());
        // coalesce(sum(...), 0) answered a numeric 0, scale 0: the JSON says 0, not 0.00.
        assertThat(stats.getTotalDisbursedAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(stats.getTotalAgentCommission()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void theAnswerIsCached_soASecondCallReadsNothing() {
        LoanRepository loans = mock(LoanRepository.class);
        LoanBatchRepository batches = mock(LoanBatchRepository.class);
        when(batches.dashboardEntityCounts()).thenReturn(COUNTS);
        DashboardServiceImpl service = new DashboardServiceImpl(loans, batches, mock(PlatformTransactionManager.class),
                Duration.ofSeconds(30));

        DashboardStatsResponse first = service.getDashboardStats();
        DashboardStatsResponse second = service.getDashboardStats();

        assertThat(second).isSameAs(first);
        verify(loans, times(1)).dashboardGroups();
        verify(batches, times(1)).dashboardEntityCounts();
    }

    @Test
    void aZeroTtlTurnsTheCacheOff() {
        LoanRepository loans = mock(LoanRepository.class);
        LoanBatchRepository batches = mock(LoanBatchRepository.class);
        when(batches.dashboardEntityCounts()).thenReturn(COUNTS);
        DashboardServiceImpl service = new DashboardServiceImpl(loans, batches, mock(PlatformTransactionManager.class),
                Duration.ZERO);

        service.getDashboardStats();
        service.getDashboardStats();

        verify(loans, times(2)).dashboardGroups();
    }

    private static Map.Entry<String, Long> entry(String key, long value) {
        return Map.entry(key, value);
    }
}
