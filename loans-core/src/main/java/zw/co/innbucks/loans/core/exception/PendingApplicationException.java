package zw.co.innbucks.loans.core.exception;

/**
 * An application refused because the same applicant (EC number or national ID) already has a loan
 * in flight. Nothing was created, so it is a conflict with existing state, not a rejected loan.
 */
public class PendingApplicationException extends ConflictException {

    private final Long pendingLoanId;

    public PendingApplicationException(Long pendingLoanId) {
        super("You have a pending loan application.");
        this.pendingLoanId = pendingLoanId;
    }

    /** Logged, never returned: the applicant is not necessarily a caller who may read that loan. */
    public Long getPendingLoanId() {
        return pendingLoanId;
    }
}
