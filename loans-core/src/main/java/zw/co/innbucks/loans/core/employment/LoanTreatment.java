package zw.co.innbucks.loans.core.employment;

/** What an employment event does to the borrower's loans that have been paid out and are being repaid. */
public enum LoanTreatment {
    /** Nothing: repayment by payroll deduction carries on. */
    NONE,
    /** Opened for an officer to decide how the loan will now be repaid, and record it. */
    REVIEW
}
