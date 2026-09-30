package zw.co.innbucks.loans.core.draft;

/** Where a saved application stands (FR-SSB-002). */
public enum LoanApplicationDraftStatus {
    /** Being filled in: can be saved again, discarded or submitted. */
    OPEN,
    /** Submitted: it is a loan now, and the draft only records which. */
    SUBMITTED
}
