package zw.co.innbucks.loans.core.disbursements;

/**
 * A pre-approved booking that was never sent because its request could not be built from what the loan holds. Like
 * every {@link BookingNotSentException}, nothing reached InnBucks; unlike the others (a failed login, a connection never
 * opened) the cause is this one booking's, so sending other bookings is not affected by it.
 */
public class BookingNotBuiltException extends BookingNotSentException {

    public BookingNotBuiltException(String message, Throwable cause) {
        super(message, cause);
    }
}
