package zw.co.innbucks.loans.core.staff.offer;

/** Where a pre-approved offer stands (FR-SGL-016, FR-SGL-018). Only an ACTIVE offer can be taken up. */
public enum StaffOfferStatus {
    /** Open until it expires. */
    ACTIVE,
    /** Lapsed unaccepted at its expiry; the next weekly run issues a new one. */
    EXPIRED,
    /** Replaced by a later run's offer while it was still open. */
    SUPERSEDED,
    /** Taken back because its holder is no longer eligible or is excluded; the reason is kept. */
    WITHDRAWN,
    /** Accepted in the SuperApp: it became a Staff Grocery Loan, named in the reason (FR-SGL-027). */
    TAKEN_UP
}
