package zw.co.innbucks.loans.core.staff.loan;

/** The borrower cannot take a Staff Grocery Loan now, for the reason given (FR-SGL-029). A 422; nothing was changed. */
public class StaffLoanDeclinedException extends RuntimeException {

    private final StaffLoanDecline decline;

    public StaffLoanDeclinedException(StaffLoanDecline decline) {
        super(decline.message());
        this.decline = decline;
    }

    public StaffLoanDecline decline() {
        return decline;
    }
}
