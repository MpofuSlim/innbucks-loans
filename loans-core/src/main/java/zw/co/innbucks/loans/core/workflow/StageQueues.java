package zw.co.innbucks.loans.core.workflow;

import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Every stage's queue: a bean for each system stage, and a {@link CheckpointQueue} built for each checkpoint as it is
 * asked for. Refuses to start if a system stage has no queue, or two.
 */
@Component
public class StageQueues {

    private final Map<SystemStage, StageQueue> queues = new EnumMap<>(SystemStage.class);
    private final LoanRepository loanRepository;
    private final CheckpointDecisionRepository checkpointDecisionRepository;

    public StageQueues(List<SystemStageQueue> queues, LoanRepository loanRepository,
                       CheckpointDecisionRepository checkpointDecisionRepository) {
        for (SystemStageQueue queue : queues) {
            if (this.queues.put(queue.stage(), queue) != null) {
                throw new IllegalStateException("Two queues for workflow stage " + queue.stage());
            }
        }
        for (SystemStage stage : SystemStage.values()) {
            if (!this.queues.containsKey(stage)) {
                throw new IllegalStateException("No queue for workflow stage " + stage);
            }
        }
        this.loanRepository = loanRepository;
        this.checkpointDecisionRepository = checkpointDecisionRepository;
    }

    public StageQueue of(SystemStage stage) {
        return queues.get(stage);
    }

    /**
     * The stage's queue.
     *
     * @throws IllegalStateException a system stage with a code this build does not know
     */
    public StageQueue of(WorkflowStage stage) {
        if (stage.isCheckpoint()) {
            return new CheckpointQueue(stage, loanRepository, checkpointDecisionRepository);
        }
        return SystemStage.of(stage.getCode()).map(queues::get)
                .orElseThrow(() -> new IllegalStateException("No queue for workflow stage " + stage.getCode()));
    }
}
