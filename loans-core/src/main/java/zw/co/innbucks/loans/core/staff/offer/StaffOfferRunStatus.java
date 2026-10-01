package zw.co.innbucks.loans.core.staff.offer;

/** How an attempt at the weekly offer run ended. */
public enum StaffOfferRunStatus {
    COMPLETED,
    /** Not run: the staff register is not reconciled against the payroll master recently enough. */
    REFUSED,
    /** Broke off with an error; nothing it did was kept, and running again picks up the whole cycle. */
    FAILED
}
