package zw.co.innbucks.loans.core.workflow;

/** How a loan leaves a checkpoint: it carries on, or the application is declined as a credit rejection. */
public enum CheckpointOutcome {
    CLEARED, DECLINED
}
