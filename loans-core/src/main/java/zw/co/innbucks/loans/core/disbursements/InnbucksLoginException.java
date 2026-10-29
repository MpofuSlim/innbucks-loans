package zw.co.innbucks.loans.core.disbursements;

/**
 * The InnBucks login failed, or answered without a token: no call that needed it was made. Carries the HTTP status at
 * most, never the refused login's body, which can echo what was sent.
 */
public class InnbucksLoginException extends RuntimeException {

    public InnbucksLoginException(String message) {
        super(message);
    }

    public InnbucksLoginException(String message, Throwable cause) {
        super(message, cause);
    }
}
