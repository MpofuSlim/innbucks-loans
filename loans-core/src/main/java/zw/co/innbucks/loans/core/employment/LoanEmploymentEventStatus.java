package zw.co.innbucks.loans.core.employment;

/** Whether a loan's employment event still waits for an officer. */
public enum LoanEmploymentEventStatus {
    /** A hold or a review in the officers' queue. */
    OPEN,
    /** Settled: nothing to do, declined when recorded, or resolved by an officer. */
    CLOSED
}
