package zw.co.reikan.loans.core.exception;

/**
 * A credit-manager approval the loan's current state does not allow (already
 * decided, not yet payroll-approved, or a non-decision status). A
 * {@link BusinessException}, so it reaches the client as a 400 carrying this
 * message — previously a bare RuntimeException, which the catch-all handler
 * turned into a 500 that read like a server fault and invited a retry.
 */
public class LoanApprovalException extends BusinessException {

    public LoanApprovalException(String message) {
        super(message);
    }
}
