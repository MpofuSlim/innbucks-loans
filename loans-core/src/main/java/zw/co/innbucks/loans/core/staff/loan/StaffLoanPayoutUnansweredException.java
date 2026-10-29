package zw.co.innbucks.loans.core.staff.loan;

/**
 * The bank's inquiry, asked for one payout because a person pressed "check now", did not answer, answered nothing that
 * could be concluded from, or broke: nothing changed. A 502: the bank, not the request, is at fault, and asking again
 * later works. An answer that could not be saved here is {@link StaffLoanPayoutNotRecordedException}.
 */
public class StaffLoanPayoutUnansweredException extends RuntimeException {

    public StaffLoanPayoutUnansweredException(String message) {
        super(message);
    }
}
