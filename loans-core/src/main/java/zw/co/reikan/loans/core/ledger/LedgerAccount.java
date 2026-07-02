package zw.co.reikan.loans.core.ledger;

/**
 * Chart of accounts for the lending bounded context. Deliberately coarse:
 * accounts exist to make every money movement a balanced double entry, not to
 * replace the general ledger of the accounting system downstream.
 */
public enum LedgerAccount {
    /** Amounts owed to us by borrowers (principal outstanding). */
    LOAN_PRINCIPAL_RECEIVABLE,
    /** Funds staged for / pushed out via the InnBucks disbursement rail. */
    DISBURSEMENT_CLEARING,
    /** Origination/processing fee revenue recognised on disbursement. */
    FEE_INCOME,
    /** Interest earned over the loan lifecycle. */
    INTEREST_RECEIVABLE,
    /** Commission owed to submitting agents. */
    AGENT_COMMISSION_PAYABLE,
    /** SSB (Ndasenda) payroll deductions in transit to us. */
    SSB_DEDUCTIONS_RECEIVABLE,
    /** Write-off / reversal suspense for compensated sagas. */
    REVERSAL_SUSPENSE
}
