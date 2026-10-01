package zw.co.innbucks.loans.core.staff.loan;

/**
 * No Staff Grocery Loan agreement has been published (POST /instrument-templates, STAFF_GROCERY_LOAN_AGREEMENT), so no
 * loan can be accepted (FR-SGL-027). A 503: nothing is wrong with the request.
 */
public class StaffLoanTermsUnavailableException extends RuntimeException {

    public StaffLoanTermsUnavailableException() {
        super("Staff Grocery Loans are not available right now. Please try again later.");
    }
}
