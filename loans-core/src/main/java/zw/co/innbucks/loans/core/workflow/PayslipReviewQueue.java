package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.PayslipFraudFlag;
import zw.co.innbucks.loans.core.loan.PayslipFraudFlagRepository;
import zw.co.innbucks.loans.core.loan.PayslipReviewStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Applications held for a payslip finding (FR-SSB-007). A hold begins when its findings are raised: at application,
 * or again when an amended payslip raises new ones.
 */
@Component
@RequiredArgsConstructor
class PayslipReviewQueue implements SystemStageQueue {

    private final LoanRepository loanRepository;
    private final PayslipFraudFlagRepository payslipFraudFlagRepository;

    @Override
    public SystemStage stage() {
        return SystemStage.PAYSLIP_REVIEW;
    }

    @Override
    public List<Waiting> waiting() {
        List<Loan> held = loanRepository.findByPayslipReviewStatusOrderByIdAsc(PayslipReviewStatus.PENDING);
        Map<Long, LocalDateTime> raised = lastRaised(held.stream().map(Loan::getId).toList());
        return held.stream()
                .map(loan -> new Waiting(loan, raised.getOrDefault(loan.getId(), loan.getCreatedDate())))
                .sorted(Comparator.comparing(Waiting::enteredAt))
                .toList();
    }

    @Override
    public Optional<LocalDateTime> enteredAt(Loan loan) {
        if (loan.getPayslipReviewStatus() != PayslipReviewStatus.PENDING) {
            return Optional.empty();
        }
        return Optional.of(lastRaised(List.of(loan.getId())).getOrDefault(loan.getId(), loan.getCreatedDate()));
    }

    @Override
    public List<Visit> endedBetween(LocalDateTime from, LocalDateTime to) {
        List<Loan> reviewed = loanRepository.findByPayslipReviewedAtBetween(from, to);
        if (reviewed.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PayslipFraudFlag>> flags = payslipFraudFlagRepository
                .findByLoanIdInOrderByIdAsc(reviewed.stream().map(Loan::getId).toList()).stream()
                .collect(Collectors.groupingBy(PayslipFraudFlag::getLoanId));
        return reviewed.stream()
                .map(loan -> new Visit(loan.getId(), flags.getOrDefault(loan.getId(), List.of()).stream()
                        .map(PayslipFraudFlag::getCreatedAt)
                        .filter(raised -> !raised.isAfter(loan.getPayslipReviewedAt()))
                        .max(Comparator.naturalOrder())
                        .orElse(null),
                        loan.getPayslipReviewedAt(), String.valueOf(loan.getPayslipReviewStatus())))
                .toList();
    }

    /** When each loan's latest findings were raised. */
    private Map<Long, LocalDateTime> lastRaised(Collection<Long> loanIds) {
        if (loanIds.isEmpty()) {
            return Map.of();
        }
        return payslipFraudFlagRepository.findByLoanIdInOrderByIdAsc(loanIds).stream()
                .collect(Collectors.toMap(PayslipFraudFlag::getLoanId, PayslipFraudFlag::getCreatedAt,
                        (a, b) -> a.isAfter(b) ? a : b));
    }
}
