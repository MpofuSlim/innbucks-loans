package zw.co.innbucks.loans.core.loan;

/**
 * One entry in a loan's credit decision log: Credit's three decisions, and the originator's
 * answer to a return for more information.
 */
public enum CreditAction {
    APPROVED, REJECTED, RETURNED, RESUBMITTED
}
