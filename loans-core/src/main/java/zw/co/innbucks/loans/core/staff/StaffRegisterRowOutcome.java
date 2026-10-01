package zw.co.innbucks.loans.core.staff;

/** What became of one row of a batch. */
public enum StaffRegisterRowOutcome {
    /** Refused at upload; its reasons are on the row. The rest of the file was loaded. */
    REJECTED,
    /** Accepted, waiting for the batch to be decided. */
    STAGED,
    /** On approval: a new staff record. */
    CREATED,
    /** On approval: an existing record changed. */
    AMENDED,
    /** On approval: the record already held exactly these values. */
    UNCHANGED,
    /** On approval: no longer valid against the register (it changed since the upload); its reasons are on the row. */
    SKIPPED
}
