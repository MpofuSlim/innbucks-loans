package zw.co.innbucks.loans.core.staff.loan;

/** Where a write-off request stands. Only APPROVED changed its loan. */
public enum StaffLoanWriteOffStatus {
    /** Proposed, waiting for FINANCE or a SUPER_ADMIN other than the proposer. */
    PENDING,
    /** Approved, and applied to the loan. */
    APPROVED,
    REJECTED,
    /** Taken back by its proposer before anyone decided it. */
    WITHDRAWN
}
