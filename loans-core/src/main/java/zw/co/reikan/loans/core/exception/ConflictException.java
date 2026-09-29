package zw.co.reikan.loans.core.exception;

/**
 * The request is valid but the resource's current state does not allow it — rendered as a 409 in
 * the standard error envelope. Not a {@link BusinessException}: that is a 400, which reads as
 * "fix your input" when the answer is "re-read the resource".
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
