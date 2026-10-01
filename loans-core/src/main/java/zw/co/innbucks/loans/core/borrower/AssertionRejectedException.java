package zw.co.innbucks.loans.core.borrower;

/**
 * An assertion that was not accepted, for {@link #getMessage() a reason} that goes to the log and the audit trail only.
 * The caller is told nothing more than that it was rejected: which check failed is help to whoever forged it.
 */
public class AssertionRejectedException extends RuntimeException {

    public AssertionRejectedException(String reason) {
        super(reason);
    }
}
