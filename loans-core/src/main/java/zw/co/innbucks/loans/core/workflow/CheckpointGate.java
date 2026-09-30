package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Whether a checkpoint holds a loan at a point (FR-SSB-014). Asked by what moves a loan past each point: the lodgement
 * job, a credit approval and the booking job, each under the loan's row lock, so a checkpoint added or decided while
 * the loan was on its way is honoured. The recovery payout, which pays a loan past BEFORE_BOOKING whose booking
 * InnBucks refused, asks under the same lock whether the checkpoints that held it there cleared it.
 */
@Component
@RequiredArgsConstructor
public class CheckpointGate {

    private final WorkflowStageRepository workflowStageRepository;
    private final LoanRepository loanRepository;
    private final CheckpointDecisionRepository checkpointDecisionRepository;

    /**
     * The first active checkpoint at the point that applies to the loan and has not decided it. The caller has the
     * loan at the point; this does not check that.
     */
    public Optional<WorkflowStage> holding(HoldPoint point, Loan loan) {
        for (WorkflowStage stage : checkpointsAt(point)) {
            if (stage.appliesTo(loan)
                    && !checkpointDecisionRepository.existsByStageCodeAndLoanId(stage.getCode(), loan.getId())) {
                return Optional.of(stage);
            }
        }
        return Optional.empty();
    }

    /**
     * For a loan that has already left the point: the first active checkpoint there that applies to it and did not
     * clear it, either because it declined the loan or because it was in force when the loan left at {@code leftAt}
     * and never decided it. Asked by the recovery payout, which pays in place of a booking InnBucks refused.
     *
     * <p>The checkpoints that count are the ones {@link #holding} asked when the loan left: active, at the point and
     * applying to it. One still active whose {@code activeSince} is no later than {@code leftAt} has been active since
     * before then. One switched on after the loan left never held it: the loan was past its point, where a checkpoint
     * holds nothing ({@link CheckpointQueue#atHoldPoint}), so the loan could neither wait there nor be decided there,
     * and counting it would hold the loan for good. A decision is only ever recorded while a checkpoint holds the
     * loan, so one that declined it counts whenever it was switched on. {@code leftAt} null means the loan left before
     * that was recorded; then only a decline counts.
     */
    public Optional<NotCleared> notCleared(HoldPoint point, Loan loan, LocalDateTime leftAt) {
        List<WorkflowStage> checkpoints = checkpointsAt(point).stream().filter(stage -> stage.appliesTo(loan)).toList();
        if (checkpoints.isEmpty()) {
            return Optional.empty();
        }
        Map<String, CheckpointOutcome> outcomes = new HashMap<>();
        for (CheckpointDecision decision : checkpointDecisionRepository.findByStageCodeInAndLoanIdIn(
                checkpoints.stream().map(WorkflowStage::getCode).toList(), List.of(loan.getId()))) {
            outcomes.put(decision.getStageCode(), decision.getOutcome());
        }
        for (WorkflowStage stage : checkpoints) {
            CheckpointOutcome outcome = outcomes.get(stage.getCode());
            if (outcome == CheckpointOutcome.DECLINED) {
                return Optional.of(new NotCleared(stage, true));
            }
            if (outcome == null && leftAt != null
                    && (stage.getActiveSince() == null || !stage.getActiveSince().isAfter(leftAt))) {
                return Optional.of(new NotCleared(stage, false));
            }
        }
        return Optional.empty();
    }

    /** A checkpoint that did not clear a loan: it declined it, or the loan is still waiting there. */
    public record NotCleared(WorkflowStage stage, boolean declined) {
    }

    /** The loans no checkpoint holds at the point, in the order given; one read for the lot, not one per loan. */
    public List<Long> withoutHeld(HoldPoint point, List<Long> loanIds) {
        List<WorkflowStage> checkpoints = checkpointsAt(point);
        if (checkpoints.isEmpty() || loanIds.isEmpty()) {
            return loanIds;
        }
        Map<Long, Loan> loans = loanRepository.findAllById(loanIds).stream()
                .collect(Collectors.toMap(Loan::getId, Function.identity()));
        Set<String> decided = decidedPairs(checkpoints.stream().map(WorkflowStage::getCode).toList(), loanIds);
        return loanIds.stream()
                .filter(id -> {
                    Loan loan = loans.get(id);
                    return loan == null || checkpoints.stream().noneMatch(stage -> stage.appliesTo(loan)
                            && !decided.contains(pair(stage.getCode(), id)));
                })
                .toList();
    }

    /** Every active checkpoint holding the loan now, wherever it is: at its point, applying, and undecided. */
    public List<WorkflowStage> pending(Loan loan) {
        List<WorkflowStage> checkpoints = workflowStageRepository.findByKindOrderByDisplayOrderAscCodeAsc(
                        StageKind.CHECKPOINT).stream()
                .filter(WorkflowStage::isActive)
                .filter(stage -> CheckpointQueue.atHoldPoint(stage.getHoldPoint(), loan) && stage.appliesTo(loan))
                .toList();
        if (checkpoints.isEmpty()) {
            return List.of();
        }
        Set<String> decided = decidedPairs(checkpoints.stream().map(WorkflowStage::getCode).toList(),
                List.of(loan.getId()));
        return checkpoints.stream().filter(stage -> !decided.contains(pair(stage.getCode(), loan.getId()))).toList();
    }

    private List<WorkflowStage> checkpointsAt(HoldPoint point) {
        return workflowStageRepository.findByKindAndHoldPointAndActiveTrueOrderByDisplayOrderAscCodeAsc(
                StageKind.CHECKPOINT, point);
    }

    private Set<String> decidedPairs(Collection<String> stageCodes, Collection<Long> loanIds) {
        if (stageCodes.isEmpty() || loanIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> decided = new HashSet<>();
        for (CheckpointDecision decision : checkpointDecisionRepository.findByStageCodeInAndLoanIdIn(stageCodes,
                loanIds)) {
            decided.add(pair(decision.getStageCode(), decision.getLoanId()));
        }
        return decided;
    }

    private static String pair(String stageCode, Long loanId) {
        return stageCode + '\u001f' + loanId;
    }
}
