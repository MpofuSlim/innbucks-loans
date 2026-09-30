package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.loan.DeductionCancellationStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Loans whose deduction was lodged with SSB and must now be cancelled, because they will not be paid (FR-SSB-023). A
 * wait begins when the cancellation is flagged and ends when an operator records it done; one withdrawn because the
 * loan was paid after all ends without anyone working it, and is not timed.
 */
@Component
@RequiredArgsConstructor
class DeductionCancellationQueue implements SystemStageQueue {

    private final LoanRepository loanRepository;

    @Override
    public SystemStage stage() {
        return SystemStage.DEDUCTION_CANCELLATION;
    }

    @Override
    public List<Waiting> waiting() {
        return loanRepository.findByDeductionCancellationStatusOrderByDeductionCancellationRequestedAtAscIdAsc(
                        DeductionCancellationStatus.REQUIRED).stream()
                .filter(loan -> loan.getDeductionCancellationRequestedAt() != null)
                .map(loan -> new Waiting(loan, loan.getDeductionCancellationRequestedAt()))
                .toList();
    }

    @Override
    public Optional<LocalDateTime> enteredAt(Loan loan) {
        return loan.getDeductionCancellationStatus() == DeductionCancellationStatus.REQUIRED
                ? Optional.ofNullable(loan.getDeductionCancellationRequestedAt()) : Optional.empty();
    }

    @Override
    public List<Visit> endedBetween(LocalDateTime from, LocalDateTime to) {
        return loanRepository.findByDeductionCancelledAtBetween(from, to).stream()
                .map(loan -> new Visit(loan.getId(), loan.getDeductionCancellationRequestedAt(),
                        loan.getDeductionCancelledAt(), String.valueOf(loan.getDeductionCancellationStatus())))
                .toList();
    }
}
