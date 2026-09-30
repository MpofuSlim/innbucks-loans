package zw.co.innbucks.loans.core.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One loan waiting at a stage: who it is, since when, against the stage's service level, and who has it.
 *
 * @param channelId   the channel it came through; absent for the portal
 * @param escalatesAt absent when the stage is never escalated
 * @param escalatedAt when this wait was escalated; absent if it has not been
 * @param assignedTo  who has it; absent when nobody does
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkItemResponse(
        String stage,
        Long loanId,
        String reference,
        String applicantName,
        BigDecimal principal,
        String channelId,
        String channelName,
        String originator,
        String originatorName,
        LocalDateTime enteredAt,
        LocalDateTime dueAt,
        LocalDateTime escalatesAt,
        BigDecimal waitingHours,
        boolean overdue,
        LocalDateTime escalatedAt,
        String assignedTo,
        String assignedToName,
        LocalDateTime assignedAt) {
}
