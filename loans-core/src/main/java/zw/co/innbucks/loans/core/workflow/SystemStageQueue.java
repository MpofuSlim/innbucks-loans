package zw.co.innbucks.loans.core.workflow;

/** The queue of one of the pipeline's own stages; one bean each, found by {@link StageQueues}. */
public interface SystemStageQueue extends StageQueue {

    SystemStage stage();
}
