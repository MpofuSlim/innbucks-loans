package zw.co.innbucks.loans.core.staff.loan;

/**
 * The Staff Grocery Loan tile in the SuperApp, in one call (FR-SGL-025, FR-SGL-030). At most one of these is set:
 * {@code loan}, the loan they hold; {@code offer}, an offer to take up ("Accept offer"); or {@code unavailable}, why
 * they cannot borrow now (FR-SGL-029). With none set and {@code canApply} true, they may apply ("Apply").
 */
public record StaffLoanHome(StaffLoanView loan, StaffLoanOfferView offer, boolean canApply,
                            StaffLoanUnavailable unavailable) {

    static StaffLoanHome holding(StaffLoanView loan) {
        return new StaffLoanHome(loan, null, false, null);
    }

    static StaffLoanHome offered(StaffLoanOfferView offer) {
        return new StaffLoanHome(null, offer, false, null);
    }

    static StaffLoanHome mayApply() {
        return new StaffLoanHome(null, null, true, null);
    }

    static StaffLoanHome unavailable(StaffLoanDecline decline) {
        return new StaffLoanHome(null, null, false, StaffLoanUnavailable.of(decline));
    }
}
