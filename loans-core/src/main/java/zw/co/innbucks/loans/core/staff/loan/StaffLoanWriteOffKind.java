package zw.co.innbucks.loans.core.staff.loan;

/** What a write-off request does to its loan (FR-GEN-011). */
public enum StaffLoanWriteOffKind {
    /** Writes off a paid-out loan: DISBURSED becomes WRITTEN_OFF. */
    WRITE_OFF,
    /** Puts back a loan written off in error: WRITTEN_OFF becomes DISBURSED. */
    WRITE_OFF_REVERSAL;

    /** The audit event for {@code action} on a request of this kind, e.g. STAFF_LOAN_WRITE_OFF_APPROVED. */
    String event(String action) {
        return "STAFF_LOAN_" + name() + "_" + action;
    }
}
