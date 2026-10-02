package zw.co.innbucks.loans.core.staff.offer;

/** Where an arrears override stands (FR-SGL-014). Only an APPROVED one, up to its last day, lets a loan be taken. */
public enum StaffArrearsOverrideStatus {
    /** Proposed, waiting for another credit manager or SUPER_ADMIN. */
    PENDING,
    /** In force up to and including its last day. */
    APPROVED,
    REJECTED,
    /** Taken back by its proposer before anyone decided it. */
    WITHDRAWN,
    /** Replaced by a later approved override for the same member. */
    SUPERSEDED,
    /** Ended by Credit before it was used. */
    REVOKED,
    /** Spent: the member took a loan under it. */
    USED
}
