package zw.co.innbucks.loans.core.staff.loan;

/**
 * The payout thread's queue had no room for what was asked, so nothing was queued: every payout it named stays READY,
 * and nothing was sent. A 503: asking again in a few minutes works.
 */
public class StaffLoanPayoutQueueFullException extends RuntimeException {

    public StaffLoanPayoutQueueFullException(String message) {
        super(message);
    }
}
