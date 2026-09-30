package zw.co.innbucks.loans.core.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * A checkpoint on one loan: holding it now (PENDING), or how it left (CLEARED or DECLINED).
 *
 * @param enteredAt  when the loan's wait at the checkpoint began
 * @param assignedTo who has a pending loan's item, if anyone
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanCheckpointResponse(
        String stage,
        String name,
        HoldPoint holdPoint,
        CheckpointStatus status,
        LocalDateTime enteredAt,
        String assignedTo,
        String reasonCode,
        String comment,
        String decidedBy,
        LocalDateTime decidedAt) {
}
