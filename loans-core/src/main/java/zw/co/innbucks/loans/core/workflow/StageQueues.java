package zw.co.innbucks.loans.core.workflow;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Every stage's queue, by stage. Refuses to start if a stage has no queue, or two. */
@Component
public class StageQueues {

    private final Map<SystemStage, StageQueue> queues = new EnumMap<>(SystemStage.class);

    public StageQueues(List<StageQueue> queues) {
        for (StageQueue queue : queues) {
            if (this.queues.put(queue.stage(), queue) != null) {
                throw new IllegalStateException("Two queues for workflow stage " + queue.stage());
            }
        }
        for (SystemStage stage : SystemStage.values()) {
            if (!this.queues.containsKey(stage)) {
                throw new IllegalStateException("No queue for workflow stage " + stage);
            }
        }
    }

    public StageQueue of(SystemStage stage) {
        return queues.get(stage);
    }
}
