package zw.co.innbucks.loans.core.staff.offer;

/**
 * Whether a member of the register may be offered a Staff Grocery Loan, and if not, why: one answer for the weekly run
 * (FR-SGL-016, FR-SGL-017), for a borrower applying on demand (FR-SGL-025) and for one accepting (FR-SGL-013), so the
 * three can never disagree. In the order they are checked.
 */
public enum StaffOfferVerdict {
    /** Not ACTIVE in employment (FR-SGL-005). */
    NOT_ACTIVE,
    /** Their grade has no limit above zero in force today (FR-SGL-005). */
    NO_LIMIT,
    /** Overdue or written off on a Staff Grocery Loan (FR-SGL-014, FR-SGL-017). */
    ARREARS,
    /** Already holds a Staff Grocery Loan (FR-SGL-013, FR-SGL-017). */
    ACTIVE_LOAN,
    /** The latest payroll reconciliation lists them as having left, or not on the payroll (FR-SGL-008). */
    PAYROLL_FLAGGED,
    /** Credit set their limit to 0 (FR-SGL-011). */
    LIMIT_ZERO,
    /** May be offered. */
    ELIGIBLE;

    /** Whether the register itself rules them out (not employed, no limit), rather than an exclusion. */
    public boolean ineligible() {
        return this == NOT_ACTIVE || this == NO_LIMIT;
    }
}
