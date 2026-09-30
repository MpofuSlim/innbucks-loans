package zw.co.innbucks.loans.core.turnaround;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * How long a loan has waited for a credit decision, against the service level (FR-PBL-030). Only a loan waiting on
 * Credit has one: SSB has accepted its deduction and Credit has not decided.
 *
 * @param queueEnteredAt when it reached Credit: SSB's approval, or the originator's last answer to a return
 * @param dueAt          when the decision is due, by the target
 * @param escalatesAt    when it is escalated if still waiting
 * @param waitingHours   how long it has waited so far
 * @param overdue        whether it is past the target
 * @param escalatedAt    when this wait was escalated; absent if it has not been
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
        LocalDateTime escalatedAt) {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    /** SSB has accepted its deduction and Credit has not decided. */
    public static boolean awaitingDecision(Loan loan) {
        return loan.getLoanApprovalStatus() == LoanApprovalStatus.APPROVED
                && loan.getInternalApprovalStatus() == InternalApprovalStatus.PENDING;
    }

    /** The loan's wait as of {@code now}; null when it is not waiting on Credit, or when it arrived is unknown. */
    public static CreditTurnaround of(Loan loan, ServiceLevel level, LocalDateTime now) {
        LocalDateTime entered = loan.creditQueueEnteredAt();
        if (!awaitingDecision(loan) || entered == null) {
            return null;
        }
        LocalDateTime due = entered.plusHours(level.getTargetHours());
        return new CreditTurnaround(entered, due, entered.plusHours(level.getEscalationHours()),
                hoursBetween(entered, now), now.isAfter(due), loan.getCreditEscalatedAt());
    }

    /** Whole and part hours, to one decimal; never negative. */
    static BigDecimal hoursBetween(LocalDateTime from, LocalDateTime to) {
        long minutes = Math.max(0, Duration.between(from, to).toMinutes());
        return BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, 1, RoundingMode.HALF_UP);
    }
}
