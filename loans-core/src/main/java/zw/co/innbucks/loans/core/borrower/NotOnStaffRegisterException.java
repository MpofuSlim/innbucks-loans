package zw.co.innbucks.loans.core.borrower;

/**
 * A genuine assertion for a phone that is not a current staff member's on the register: the Staff Grocery Loan is not
 * for them (FR-SGL-029 asks for a plain reason, not a bare refusal).
 */
public class NotOnStaffRegisterException extends RuntimeException {

    public static final String MESSAGE = "The Staff Grocery Loan is for InnBucks staff. Your number is not on the staff"
            + " register: if you work for InnBucks, ask Human Capital to check your details.";

    public NotOnStaffRegisterException() {
        super(MESSAGE);
    }
}
