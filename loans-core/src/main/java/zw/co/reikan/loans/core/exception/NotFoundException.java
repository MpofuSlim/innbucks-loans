package zw.co.reikan.loans.core.exception;

/** The addressed resource does not exist — rendered as a 404 in the standard error envelope. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
