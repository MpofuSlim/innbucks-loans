package zw.co.innbucks.loans.core.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * How a loan left a checkpoint.
 *
 * @param reasonCode the credit reason code of a decline; absent when cleared
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointDecisionResponse(
        String stage,
        String stageName,
        HoldPoint holdPoint,
        Long loanId,
        String reference,
        LocalDateTime enteredAt,
        CheckpointOutcome outcome,
        String reasonCode,
        String comment,
        String decidedBy,
        LocalDateTime decidedAt) {

    static CheckpointDecisionResponse of(CheckpointDecision decision, WorkflowStage stage, String reference) {
        return new CheckpointDecisionResponse(decision.getStageCode(), stage.getName(), stage.getHoldPoint(),
                decision.getLoanId(), reference, decision.getEnteredAt(), decision.getOutcome(),
                decision.getReasonCode(), decision.getComment(), decision.getDecidedBy(), decision.getDecidedAt());
    }
}
