package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.CreditDecision;
import zw.co.innbucks.loans.core.loan.CreditDecisionRepository;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applications SSB has accepted and Credit has not decided (FR-SSB-015). A wait begins at SSB's approval, and again
 * when the originator answers a return; it ends at an approval, a rejection or a return.
 */
@Component
@RequiredArgsConstructor
class CreditDecisionQueue implements SystemStageQueue {

    /** The decisions a wait ends in; a resubmission starts one. */
    static final EnumSet<CreditAction> DECISIONS =
            EnumSet.of(CreditAction.APPROVED, CreditAction.REJECTED, CreditAction.RETURNED);

    private final LoanRepository loanRepository;
    private final CreditDecisionRepository creditDecisionRepository;

    @Override
    public SystemStage stage() {
        return SystemStage.CREDIT_DECISION;
    }

    @Override
    public List<Waiting> waiting() {
        return loanRepository.findAwaitingCreditDecision().stream()
                .filter(loan -> loan.creditQueueEnteredAt() != null)
                .map(loan -> new Waiting(loan, loan.creditQueueEnteredAt()))
                .sorted(Comparator.comparing(Waiting::enteredAt))
                .toList();
    }

    @Override
    public Optional<LocalDateTime> enteredAt(Loan loan) {
        boolean awaiting = loan.getLoanApprovalStatus() == LoanApprovalStatus.APPROVED
                && loan.getInternalApprovalStatus() == InternalApprovalStatus.PENDING;
        return awaiting ? Optional.ofNullable(loan.creditQueueEnteredAt()) : Optional.empty();
    }

    @Override
    public List<Visit> endedBetween(LocalDateTime from, LocalDateTime to) {
        List<CreditDecision> decisions =
                creditDecisionRepository.findByActionInAndPerformedAtBetweenOrderByIdAsc(DECISIONS, from, to);
        if (decisions.isEmpty()) {
            return List.of();
        }
        List<Long> loanIds = decisions.stream().map(CreditDecision::getLoanId).distinct().toList();
        Map<Long, LocalDateTime> approvedAt = new HashMap<>();
        for (Object[] row : loanRepository.findDateApprovedByIdIn(loanIds)) {
            if (row[1] != null) {
                approvedAt.put((Long) row[0], (LocalDateTime) row[1]);
            }
        }
        Map<Long, List<LocalDateTime>> resubmittedAt = new HashMap<>();
        for (CreditDecision resubmission : creditDecisionRepository
                .findByLoanIdInAndActionOrderByPerformedAtAsc(loanIds, CreditAction.RESUBMITTED)) {
            resubmittedAt.computeIfAbsent(resubmission.getLoanId(), id -> new ArrayList<>())
                    .add(resubmission.getPerformedAt());
        }
        return decisions.stream()
                .map(decision -> new Visit(decision.getLoanId(),
                        enteredBefore(decision, approvedAt.get(decision.getLoanId()),
                                resubmittedAt.getOrDefault(decision.getLoanId(), List.of())),
                        decision.getPerformedAt(), decision.getAction().name()))
                .toList();
    }

    /**
     * When the loan reached Credit for the wait this decision ended: its last resubmission before the decision, else
     * SSB's approval if that came first. Null when neither did: the loan was decided before SSB answered.
     */
    static LocalDateTime enteredBefore(CreditDecision decision, LocalDateTime approvedAt,
                                       List<LocalDateTime> resubmissions) {
        LocalDateTime decidedAt = decision.getPerformedAt();
        LocalDateTime entered = resubmissions.stream()
                .filter(at -> !at.isAfter(decidedAt))
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (entered == null && approvedAt != null && !approvedAt.isAfter(decidedAt)) {
            entered = approvedAt;
        }
        return entered;
    }
}
