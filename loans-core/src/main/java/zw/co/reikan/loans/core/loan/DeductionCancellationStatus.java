package zw.co.reikan.loans.core.loan;

/**
 * Where a loan's lodged Ndasenda (SSB payroll) deduction stands once the loan will not be paid.
 * Null on a loan means there is nothing to cancel.
 *
 * <p>Values are frozen once the column exists: ddl-auto=update creates it with a CHECK constraint
 * listing these names and never alters that constraint, so a value added later fails every insert
 * in an existing database. Record any later state (e.g. a cancellation we send ourselves) in new
 * columns instead.</p>
 */
public enum DeductionCancellationStatus {
    /** The deduction is (or may be) live at Ndasenda and someone must cancel it. */
    REQUIRED,
    /** An operator cancelled it through Ndasenda's own portal and recorded that here. */
    CANCELLED_EXTERNALLY
}
