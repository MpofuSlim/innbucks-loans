package zw.co.innbucks.loans.core.staff.loan;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The cash payouts awaiting disbursement in one payout status: how many, and the oldest by acceptance and (while
 * SENDING) by claim. What the payout gauges are read from.
 *
 * @param loans            how many
 * @param oldestAcceptedAt the earliest acceptance among them, UTC
 * @param oldestClaimedAt  the earliest claim among them, UTC; null unless they are SENDING
 */
public record StaffLoanPayoutBacklog(StaffLoanPayoutStatus status, Long loans, LocalDateTime oldestAcceptedAt,
                                     LocalDateTime oldestClaimedAt) {

    /**
     * How long the oldest has waited in this status at {@code now}, as near as is recorded: since its claim while
     * SENDING, else since it was accepted. Zero when there are none.
     */
    public Duration oldestAge(LocalDateTime now) {
        LocalDateTime since = oldestClaimedAt != null ? oldestClaimedAt : oldestAcceptedAt;
        if (since == null || since.isAfter(now)) {
            return Duration.ZERO;
        }
        return Duration.between(since, now);
    }
}
