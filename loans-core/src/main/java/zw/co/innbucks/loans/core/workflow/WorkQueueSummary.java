package zw.co.innbucks.loans.core.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One stage's queue as it stands.
 *
 * @param unassigned   waiting items nobody has; absent where items are not assigned
 * @param assignedToMe waiting items the caller has; absent where items are not assigned
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkQueueSummary(
        String stage,
        String name,
        AssignmentMode assignment,
        int targetHours,
        Integer escalationHours,
        long waiting,
        long overdue,
        long escalated,
        Long unassigned,
        Long assignedToMe) {
}
