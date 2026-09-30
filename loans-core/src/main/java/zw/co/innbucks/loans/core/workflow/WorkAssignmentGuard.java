package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.loan.Loan;

/**
 * Keeps an assigned item to its assignee where the stage says so (FR-SSB-014): at an EXCLUSIVE stage, only the person
 * an item is assigned to may act on it until it is released or reassigned. Called by each stage's action, under the
 * loan's lock, before anything changes.
 */
@Component
@RequiredArgsConstructor
public class WorkAssignmentGuard {

    private final WorkflowStageRepository workflowStageRepository;
    private final StageQueues stageQueues;
    private final WorkItemRepository workItemRepository;

    /**
     * @throws ConflictException the stage is EXCLUSIVE and the loan's item is assigned to someone else
     */
    public void requireMayAct(SystemStage stage, Loan loan, String username) {
        WorkflowStage config = workflowStageRepository.findById(stage.name()).orElse(null);
        if (config == null || config.getAssignment() != AssignmentMode.EXCLUSIVE) {
            return;
        }
        stageQueues.of(stage).enteredAt(loan)
                .flatMap(entered -> workItemRepository.findByStageCodeAndLoanIdAndEnteredAt(stage.name(), loan.getId(),
                        entered))
                .filter(item -> item.getAssignedTo() != null
                        && !StringUtils.equalsIgnoreCase(item.getAssignedTo(), username))
                .ifPresent(item -> {
                    throw new ConflictException(String.format("Loan %s's %s is assigned to %s; only they can act on it"
                            + " until it is released or reassigned", loan.getReference(), config.getName(),
                            item.getAssignedTo()));
                });
    }
}
