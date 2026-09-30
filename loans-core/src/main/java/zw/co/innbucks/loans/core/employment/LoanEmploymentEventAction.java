package zw.co.innbucks.loans.core.employment;

/** What an employment event did to one loan. */
public enum LoanEmploymentEventAction {
    /** Nothing: the treatment was CONTINUE or NONE. */
    NONE,
    /** The application is held until an officer releases or declines it. */
    HOLD,
    /** The application was declined when the event was recorded. */
    DECLINE,
    /** The paid-out loan is open for an officer's review. */
    REVIEW
}
