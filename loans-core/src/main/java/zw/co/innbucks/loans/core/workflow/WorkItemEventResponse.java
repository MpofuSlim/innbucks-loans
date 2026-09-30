package zw.co.innbucks.loans.core.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * An assignment, reassignment, release or escalation of one of a loan's work items.
 *
 * @param enteredAt when the wait it belongs to began
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkItemEventResponse(
        Long id,
        String stage,
        Long loanId,
        LocalDateTime enteredAt,
        WorkItemAction action,
        String fromUser,
        String toUser,
        String performedBy,
        LocalDateTime performedAt) {
}
