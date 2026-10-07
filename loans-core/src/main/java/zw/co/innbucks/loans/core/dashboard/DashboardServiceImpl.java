package zw.co.innbucks.loans.core.dashboard;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.api.DashboardStatsResponse;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanBatchRepository;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The admin dashboard: two statements, not nine. Every loan figure is folded out of one grouped scan of loans
 * ({@link LoanRepository#dashboardGroups()}); it used to be five whole-table reads (the total, each status map, the
 * loans awaiting Credit and each SUCCESS sum) plus three entity counts, now one statement
 * ({@link LoanBatchRepository#dashboardEntityCounts()}). The numbers are the same, to the scale of every sum.
 *
 * <p>The answer is cached for {@code innbucks.dashboard.cache-ttl} (30s; zero turns it off), one entry. That is safe
 * only because the dashboard is platform-wide and SUPER_ADMIN only: every caller who can reach it gets the same
 * answer. A dashboard scoped by caller (merchant, agent, channel) must put the scope in the key or not cache. A miss
 * is computed once however many callers are waiting, inside a read-only transaction; a hit takes no connection.</p>
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private static final String PLATFORM = "platform";

    private final LoanRepository loanRepository;
    private final LoanBatchRepository loanBatchRepository;
    private final TransactionTemplate readOnly;
    private final Cache<String, DashboardStatsResponse> cache;

    public DashboardServiceImpl(LoanRepository loanRepository, LoanBatchRepository loanBatchRepository,
                                PlatformTransactionManager transactionManager,
                                @Value("${innbucks.dashboard.cache-ttl:PT30S}") Duration cacheTtl) {
        this.loanRepository = loanRepository;
        this.loanBatchRepository = loanBatchRepository;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.cache = cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative() ? null
                : Caffeine.newBuilder().expireAfterWrite(cacheTtl).maximumSize(1).build();
    }

    @Override
    public DashboardStatsResponse getDashboardStats() {
        return cache == null ? compute() : cache.get(PLATFORM, key -> compute());
    }

    private DashboardStatsResponse compute() {
        return readOnly.execute(status -> fold(loanRepository.dashboardGroups(),
                loanBatchRepository.dashboardEntityCounts()));
    }

    /**
     * The response, from the grouped rows. Each figure keeps the meaning of the query it replaced: the total counts
     * every loan; a status map counts the loans with that status (a loan with none is in the total only); the SUCCESS
     * sums start from zero and add each group's sum, so they are 0 when nothing was paid and carry the column's
     * scale otherwise, as {@code coalesce(sum(...), 0)} did.
     */
    static DashboardStatsResponse fold(Iterable<DashboardLoanGroup> groups, DashboardEntityCounts counts) {
        Map<String, Long> byApproval = seededCounts(LoanApprovalStatus.values());
        Map<String, Long> byDisbursement = seededCounts(LoanDisbursementStatus.values());
        long total = 0;
        long pendingCredit = 0;
        BigDecimal disbursed = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        for (DashboardLoanGroup group : groups) {
            total += group.loans();
            if (group.approvalStatus() != null) {
                byApproval.merge(group.approvalStatus().name(), group.loans(), Long::sum);
            }
            if (group.disbursementStatus() != null) {
                byDisbursement.merge(group.disbursementStatus().name(), group.loans(), Long::sum);
            }
            if (group.approvalStatus() == LoanApprovalStatus.APPROVED
                    && group.internalApprovalStatus() == InternalApprovalStatus.PENDING) {
                pendingCredit += group.loans();
            }
            if (group.disbursementStatus() == LoanDisbursementStatus.SUCCESS) {
                if (group.disbursedAmount() != null) {
                    disbursed = disbursed.add(group.disbursedAmount());
                }
                if (group.agentCommission() != null) {
                    commission = commission.add(group.agentCommission());
                }
            }
        }
        return DashboardStatsResponse.builder()
                .totalLoans(total)
                .loansBySsbApprovalStatus(Collections.unmodifiableMap(byApproval))
                .pendingCreditApprovals(pendingCredit)
                .loansByDisbursementStatus(Collections.unmodifiableMap(byDisbursement))
                .totalDisbursedAmount(disbursed)
                .totalAgentCommission(commission)
                .merchantCount(counts.merchants())
                .userCount(counts.users())
                .batchCount(counts.batches())
                .build();
    }

    private static <E extends Enum<E>> Map<String, Long> seededCounts(E[] values) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (E value : values) {
            counts.put(value.name(), 0L);
        }
        return counts;
    }
}
