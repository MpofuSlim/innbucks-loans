package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEvent;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventRepository;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

/**
 * Applications held, and paid loans opened for review, by an employment event (FR-SSB-024). A loan waits from its
 * oldest open hold or review; each one ends when an officer resolves it.
 */
@Component
@RequiredArgsConstructor
class EmploymentEventReviewQueue implements SystemStageQueue {

    private static final BinaryOperator<LocalDateTime> EARLIER = (a, b) -> a.isBefore(b) ? a : b;

    private final LoanRepository loanRepository;
    private final LoanEmploymentEventRepository loanEmploymentEventRepository;

    @Override
    public SystemStage stage() {
        return SystemStage.EMPLOYMENT_EVENT_REVIEW;
    }

    @Override
    public List<Waiting> waiting() {
        Map<Long, LocalDateTime> since = loanEmploymentEventRepository
                .findByStatusOrderByIdAsc(LoanEmploymentEventStatus.OPEN).stream()
                .collect(Collectors.toMap(LoanEmploymentEvent::getLoanId, LoanEmploymentEvent::getCreatedAt, EARLIER));
        if (since.isEmpty()) {
            return List.of();
        }
        return loanRepository.findAllById(since.keySet()).stream()
                .map(loan -> new Waiting(loan, since.get(loan.getId())))
                .sorted(Comparator.comparing(Waiting::enteredAt).thenComparing(waiting -> waiting.loan().getId()))
                .toList();
    }

    @Override
    public Optional<LocalDateTime> enteredAt(Loan loan) {
        return loanEmploymentEventRepository.findByLoanIdOrderByIdAsc(loan.getId()).stream()
                .filter(row -> row.getStatus() == LoanEmploymentEventStatus.OPEN)
                .map(LoanEmploymentEvent::getCreatedAt)
                .reduce(EARLIER);
    }

    @Override
    public List<Visit> endedBetween(LocalDateTime from, LocalDateTime to) {
        return loanEmploymentEventRepository.findByResolvedAtBetweenOrderByIdAsc(from, to).stream()
                .filter(row -> row.getOutcome() != null)
                .map(row -> new Visit(row.getLoanId(), row.getCreatedAt(), row.getResolvedAt(),
                        row.getOutcome().name()))
                .toList();
    }
}
