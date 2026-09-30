package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.util.Collection;
import java.util.Collections;
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
 * the loan was on its way is honoured.
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
