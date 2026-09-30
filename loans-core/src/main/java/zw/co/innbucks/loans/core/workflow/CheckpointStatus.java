package zw.co.innbucks.loans.core.workflow;

/** Where a loan stands at a checkpoint: held there now, or how it left. */
public enum CheckpointStatus {
    PENDING, CLEARED, DECLINED
}
