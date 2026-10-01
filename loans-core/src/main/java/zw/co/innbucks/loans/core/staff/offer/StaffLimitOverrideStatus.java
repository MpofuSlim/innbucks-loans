package zw.co.innbucks.loans.core.staff.offer;

/** Where a limit override stands (FR-SGL-011). Only an APPROVED override sets an offer's amount. */
public enum StaffLimitOverrideStatus {
    /** Proposed, waiting for another credit manager or SUPER_ADMIN. */
    PENDING,
    /** In force while the member holds the grade it was set for. */
    APPROVED,
    REJECTED,
    /** Taken back by its proposer before anyone decided it. */
    WITHDRAWN,
    /** Replaced by a later approved override for the same member. */
    SUPERSEDED,
    /** Ended by Credit: the member's grade limit applies again. */
    REVOKED
}
