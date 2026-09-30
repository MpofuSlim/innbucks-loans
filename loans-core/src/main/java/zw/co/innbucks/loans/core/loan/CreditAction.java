package zw.co.innbucks.loans.core.loan;

/**
 * One entry in a loan's credit decision log: Credit's three decisions, the originator's answer to a
 * return for more information, and a referral to a higher credit authority (FR-PBL-028).
 */
public enum CreditAction {
    APPROVED, REJECTED, RETURNED, RESUBMITTED, REFERRED
}
