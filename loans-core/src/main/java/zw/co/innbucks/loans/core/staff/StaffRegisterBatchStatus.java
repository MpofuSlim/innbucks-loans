package zw.co.innbucks.loans.core.staff;

/** Where a submission to the staff register stands (FR-SGL-004). Only PENDING ever changes. */
public enum StaffRegisterBatchStatus {
    /** Submitted, waiting for a second person. Nothing has reached the register. */
    PENDING,
    /** Applied to the register. */
    APPROVED,
    /** Refused by the checker, with their reason. Nothing reached the register. */
    REJECTED,
    /** Taken back by whoever submitted it, before anyone decided it. */
    WITHDRAWN
}
