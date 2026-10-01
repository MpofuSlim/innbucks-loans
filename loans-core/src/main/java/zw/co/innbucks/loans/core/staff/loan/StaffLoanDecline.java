package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.staff.offer.StaffOfferVerdict;

/**
 * Why a borrower cannot take a Staff Grocery Loan, in words they can act on (FR-SGL-029). The app shows {@code message}
 * as it is and may branch on the name; neither names a score, a rule or a system.
 */
public enum StaffLoanDecline {

    NOT_ACTIVE_EMPLOYEE("Staff Grocery Loans are for staff in active employment. If your employment status is wrong,"
            + " please contact Human Capital."),
    GRADE_NOT_ELIGIBLE("Your grade does not qualify for a Staff Grocery Loan at the moment."),
    HAS_ACTIVE_LOAN("You already have a Staff Grocery Loan. You can take another once it has been repaid."),
    OVERDUE_BALANCE("You have an overdue loan balance with InnBucks. Please settle it before taking a new Staff Grocery"
            + " Loan."),
    DETAILS_NOT_CONFIRMED("We could not confirm your details with payroll. Please contact Human Capital."),
    NOT_AVAILABLE_TO_YOU("A Staff Grocery Loan is not available to you at the moment. Please contact the Credit"
            + " department."),
    OFFER_NOT_AVAILABLE("This offer is no longer available. Apply again to see what you can borrow."),
    TEMPORARILY_UNAVAILABLE("Staff Grocery Loans are not available right now. Please try again later.");

    private final String message;

    StaffLoanDecline(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    /** The decline for a member who may not be offered; never called for an ELIGIBLE one. */
    static StaffLoanDecline of(StaffOfferVerdict verdict) {
        return switch (verdict) {
            case NOT_ACTIVE -> NOT_ACTIVE_EMPLOYEE;
            case NO_LIMIT -> GRADE_NOT_ELIGIBLE;
            case ARREARS -> OVERDUE_BALANCE;
            case ACTIVE_LOAN -> HAS_ACTIVE_LOAN;
            case PAYROLL_FLAGGED -> DETAILS_NOT_CONFIRMED;
            case LIMIT_ZERO -> NOT_AVAILABLE_TO_YOU;
            case ELIGIBLE -> throw new IllegalArgumentException("An eligible member is not declined");
        };
    }
}
