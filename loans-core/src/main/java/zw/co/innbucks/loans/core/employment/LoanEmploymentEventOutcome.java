package zw.co.innbucks.loans.core.employment;

/** How a loan's employment event ended. */
public enum LoanEmploymentEventOutcome {
    /** A held application was released to carry on. */
    RELEASED,
    /** The application was declined, when the event was recorded or when an officer resolved the hold. */
    DECLINED,
    /** A paid-out loan was reviewed, and what will be done recorded. */
    REVIEWED
}
