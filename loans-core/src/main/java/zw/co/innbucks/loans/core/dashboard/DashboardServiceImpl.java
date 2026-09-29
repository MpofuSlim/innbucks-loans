package zw.co.innbucks.loans.core.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.api.DashboardStatsResponse;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanBatchRepository;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final LoanRepository loanRepository;
    private final MerchantRepository merchantRepository;
    private final UserRepository userRepository;
    private final LoanBatchRepository loanBatchRepository;

    @Override
    @Transactional(readOnly = true)
    public DashboardStatsResponse getDashboardStats() {
        Map<String, Long> byApproval = seededCounts(LoanApprovalStatus.values());
        for (Object[] row : loanRepository.countGroupedByApprovalStatus()) {
            if (row[0] != null) {
                byApproval.put(((LoanApprovalStatus) row[0]).name(), (Long) row[1]);
            }
        }

        Map<String, Long> byDisbursement = seededCounts(LoanDisbursementStatus.values());
        for (Object[] row : loanRepository.countGroupedByDisbursementStatus()) {
            if (row[0] != null) {
                byDisbursement.put(((LoanDisbursementStatus) row[0]).name(), (Long) row[1]);
            }
        }

        return DashboardStatsResponse.builder()
                .totalLoans(loanRepository.count())
                .loansBySsbApprovalStatus(byApproval)
                .pendingCreditApprovals(loanRepository.countByLoanApprovalStatusAndInternalApprovalStatus(
                        LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING))
                .loansByDisbursementStatus(byDisbursement)
                .totalDisbursedAmount(loanRepository.sumDisbursedAmountForSuccessfulDisbursements())
                .totalAgentCommission(loanRepository.sumAgentCommissionForSuccessfulDisbursements())
                .merchantCount(merchantRepository.count())
                .userCount(userRepository.count())
                .batchCount(loanBatchRepository.count())
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
