package zw.co.innbucks.loans.core.workflow;

/**
 * Where a stage comes from: the pipeline's own stages, enforced in code, or a checkpoint an administrator has added
 * at one of the pipeline's {@link HoldPoint}s.
 */
public enum StageKind {
    SYSTEM, CHECKPOINT
}
