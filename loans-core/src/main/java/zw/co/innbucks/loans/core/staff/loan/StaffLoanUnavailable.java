package zw.co.innbucks.loans.core.staff.loan;

/** Why the borrower cannot take a loan now, as {@link StaffLoanDecline} names and words it (FR-SGL-029). */
public record StaffLoanUnavailable(StaffLoanDecline reason, String message) {

    static StaffLoanUnavailable of(StaffLoanDecline decline) {
        return new StaffLoanUnavailable(decline, decline.message());
    }
}
