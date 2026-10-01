package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;

/**
 * What a paid-out loan needs once its borrower stops being ACTIVE (FR-SGL-007, BRD 3.8), and so who is told.
 */
public enum EmploymentFlagAction {

    /** They left (RESIGNED, TERMINATED): recovered from their terminal benefits. Human Capital and Payroll are told. */
    RECOVER_FROM_TERMINAL_BENEFITS,

    /**
     * Still employed but not being paid as usual (SUSPENDED, UNPAID_LEAVE): what becomes of the due date is Credit's
     * decision (BRD 3.8), so Credit is told.
     */
    CREDIT_TO_DECIDE;

    /** The action for a borrower now {@code status}; none while they are ACTIVE. */
    public static EmploymentFlagAction of(StaffEmploymentStatus status) {
        if (status == null || status == StaffEmploymentStatus.ACTIVE) {
            return null;
        }
        return status.hasLeft() ? RECOVER_FROM_TERMINAL_BENEFITS : CREDIT_TO_DECIDE;
    }
}
