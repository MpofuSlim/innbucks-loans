package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.CreditDecision;
import zw.co.innbucks.loans.core.loan.CreditDecisionRepository;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Applications Credit returned for more information, waiting on their originator (FR-PBL-027). A wait begins at the
 * return and ends when the originator answers it, or when Credit rejects the application instead.
 */
@Component
@RequiredArgsConstructor
class MoreInformationQueue implements StageQueue {

    private final LoanRepository loanRepository;
    private final CreditDecisionRepository creditDecisionRepository;

    @Override
    public SystemStage stage() {
        return SystemStage.MORE_INFORMATION;
    }

    @Override
    public List<Waiting> waiting() {
        return loanRepository.findByInternalApprovalStatusOrderByIdAsc(InternalApprovalStatus.RETURNED).stream()
                .filter(loan -> loan.getInternalApprovalDate() != null)
                .map(loan -> new Waiting(loan, loan.getInternalApprovalDate()))
                .sorted(Comparator.comparing(Waiting::enteredAt))
                .toList();
    }

    @Override
    public Optional<LocalDateTime> enteredAt(Loan loan) {
        return loan.getInternalApprovalStatus() == InternalApprovalStatus.RETURNED
                ? Optional.ofNullable(loan.getInternalApprovalDate()) : Optional.empty();
    }

    @Override
    public List<Visit> endedBetween(LocalDateTime from, LocalDateTime to) {
        List<CreditDecision> ends = creditDecisionRepository.findByActionInAndPerformedAtBetweenOrderByIdAsc(
                EnumSet.of(CreditAction.RESUBMITTED, CreditAction.REJECTED), from, to);
        if (ends.isEmpty()) {
            return List.of();
        }
        Map<Long, List<CreditDecision>> logs = creditDecisionRepository
                .findByLoanIdInOrderByIdAsc(ends.stream().map(CreditDecision::getLoanId).distinct().toList()).stream()
                .collect(Collectors.groupingBy(CreditDecision::getLoanId));
        List<Visit> visits = new ArrayList<>();
        for (CreditDecision end : ends) {
            // Only an action that answered a return ends a wait here: the entry just before it is the return.
            logs.getOrDefault(end.getLoanId(), List.of()).stream()
                    .filter(entry -> entry.getId() < end.getId())
                    .max(Comparator.comparing(CreditDecision::getId))
                    .filter(previous -> previous.getAction() == CreditAction.RETURNED)
                    .ifPresent(returned -> visits.add(new Visit(end.getLoanId(), returned.getPerformedAt(),
                            end.getPerformedAt(), end.getAction().name())));
        }
        return visits;
    }
}
