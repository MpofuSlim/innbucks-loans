package zw.co.innbucks.loans.core.staff;

/** Where a proposal to retire or rename a grade stands. Only PENDING ever changes. */
public enum StaffGradeChangeStatus {
    /** Proposed, waiting for a second person to approve or reject it. */
    PENDING,
    /** Carried out. */
    APPROVED,
    /** Refused by the checker, with their reason. */
    REJECTED,
    /** Taken back by whoever proposed it, before anyone decided it. */
    WITHDRAWN
}
