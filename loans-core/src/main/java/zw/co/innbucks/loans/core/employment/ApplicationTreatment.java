package zw.co.innbucks.loans.core.employment;

/** What an employment event does to the borrower's applications that have not been paid out. */
public enum ApplicationTreatment {
    /** Nothing: the application carries on. */
    CONTINUE,
    /** Kept from SSB lodgement, credit approval and booking until an officer releases or declines it. */
    HOLD,
    /** Declined at once, as a credit rejection for employment; a deduction already lodged is flagged for cancellation. */
    DECLINE
}
