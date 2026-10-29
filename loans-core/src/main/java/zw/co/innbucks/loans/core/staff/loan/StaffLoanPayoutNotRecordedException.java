package zw.co.innbucks.loans.core.staff.loan;

/**
 * The bank's inquiry, asked for one payout because a person pressed "check now", answered, and what it said could not
 * be saved here (a lock that timed out, a database that could not be reached): nothing changed. A 503: the fault is
 * ours, not the bank's, and asking again in a minute works.
 */
public class StaffLoanPayoutNotRecordedException extends RuntimeException {

    public StaffLoanPayoutNotRecordedException(String message) {
        super(message);
    }
}
