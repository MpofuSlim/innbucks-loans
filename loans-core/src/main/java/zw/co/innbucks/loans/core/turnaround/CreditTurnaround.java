package zw.co.innbucks.loans.core.turnaround;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.workflow.WaitHours;
import zw.co.innbucks.loans.core.workflow.WorkItem;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * How long a loan has waited for a credit decision, against the CREDIT_DECISION stage's service level (FR-PBL-030).
 * Only a loan waiting on Credit has one: SSB has accepted its deduction and Credit has not decided.
 *
 * @param queueEnteredAt when it reached Credit: SSB's approval, or the originator's last answer to a return
 * @param dueAt          when the decision is due, by the target
 * @param escalatesAt    when it is escalated if still waiting; absent when the stage is never escalated
 * @param waitingHours   how long it has waited so far
 * @param overdue        whether it is past the target
 * @param escalatedAt    when this wait was escalated; absent if it has not been
 * @param assignedTo     who has it in the credit queue; absent when nobody does
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "How long the loan has waited for a credit decision, against the service level (FR-PBL-030);"
        + " only on a loan waiting on Credit")
public record CreditTurnaround(
        LocalDateTime queueEnteredAt,
        LocalDateTime dueAt,
        LocalDateTime escalatesAt,
        BigDecimal waitingHours,
        boolean overdue,
        LocalDateTime escalatedAt,
        String assignedTo) {

    /** SSB has accepted its deduction and Credit has not decided. */
    public static boolean awaitingDecision(Loan loan) {
        return loan.getLoanApprovalStatus() == LoanApprovalStatus.APPROVED
                && loan.getInternalApprovalStatus() == InternalApprovalStatus.PENDING;
    }

    /**
     * The loan's wait as of {@code now}; null when it is not waiting on Credit, or when it arrived is unknown.
     *
     * @param item the loan's work item for this wait, if it has one
     */
    public static CreditTurnaround of(Loan loan, WorkflowStage stage, WorkItem item, LocalDateTime now) {
        LocalDateTime entered = loan.creditQueueEnteredAt();
        if (!awaitingDecision(loan) || entered == null) {
            return null;
        }
        boolean current = item != null && entered.equals(item.getEnteredAt());
        LocalDateTime due = entered.plusHours(stage.getTargetHours());
        return new CreditTurnaround(entered, due,
                stage.getEscalationHours() == null ? null : entered.plusHours(stage.getEscalationHours()),
                WaitHours.between(entered, now), now.isAfter(due),
                current ? item.getEscalatedAt() : null, current ? item.getAssignedTo() : null);
    }
}
