package zw.co.innbucks.loans.core.staff;

/** Where a change to the grade-to-limit matrix stands (FR-SGL-010). Only PENDING ever changes. */
public enum StaffGradeLimitChangeStatus {
    /** Proposed, waiting for a second person to approve or reject it. */
    PENDING,
    /** In the matrix: the grade's limit from its effective date. */
    APPROVED,
    /** Refused by the checker, with their reason. */
    REJECTED,
    /** Taken back by whoever proposed it, before anyone decided it. */
    WITHDRAWN,
    /** Approved, then replaced by a later approval for the same grade and date before that date arrived. */
    SUPERSEDED,
    /** Approved, then taken out of the matrix when its grade was retired or renamed. */
    RETIRED
}
