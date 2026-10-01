package zw.co.innbucks.loans.core.staff;

/**
 * A staff member's employment status on the register. Only ACTIVE staff may borrow (FR-SGL-005); any other status
 * stops new offers at once (FR-SGL-007).
 */
public enum StaffEmploymentStatus {
    ACTIVE,
    RESIGNED,
    TERMINATED,
    SUSPENDED,
    UNPAID_LEAVE;

    /** Whether they no longer work here: RESIGNED or TERMINATED. Suspended and unpaid-leave staff are still employed. */
    public boolean hasLeft() {
        return this == RESIGNED || this == TERMINATED;
    }
}
