package zw.co.innbucks.loans.core.staff.loan;

/**
 * The agreement the borrower accepted is not the one that would be signed now: a new version was published, or the
 * terms moved (the due date crosses into a new month at midnight). A 409; they are shown the current terms again.
 */
public class StaffLoanTermsChangedException extends RuntimeException {

    public StaffLoanTermsChangedException() {
        super("The loan terms have changed since you read them. Please review them again before accepting.");
    }
}
