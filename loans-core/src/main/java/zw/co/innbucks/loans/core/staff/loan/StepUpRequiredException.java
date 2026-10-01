package zw.co.innbucks.loans.core.staff.loan;

/**
 * Accepting a loan needs the borrower's PIN or biometrics just now (FR-SGL-028), and the assertion sent is genuine but
 * not that: too old, or authenticated another way. A 401: the app asks for the PIN again and retries.
 */
public class StepUpRequiredException extends RuntimeException {

    public StepUpRequiredException() {
        super("Confirm with your PIN or biometrics to accept the loan.");
    }
}
