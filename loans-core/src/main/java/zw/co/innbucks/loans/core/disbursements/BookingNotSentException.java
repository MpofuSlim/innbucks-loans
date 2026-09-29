package zw.co.innbucks.loans.core.disbursements;

/**
 * The pre-approved booking never reached InnBucks: the login failed, or the connection to the
 * booking endpoint was never opened. InnBucks books AND pays on that one call, so this is the only
 * booking failure that is safe to retry as if nothing happened — and it must not be recorded as a
 * refusal (that makes the loan eligible for a manual payout) or as an unknown outcome (that holds it
 * for good). The loan stays PENDING and the next run tries again.
 */
public class BookingNotSentException extends RuntimeException {

    public BookingNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
