package zw.co.innbucks.loans.core.loan;

import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;

/** The deposit rail's answer to one payout call. Never persisted — mapped onto {@link LoanDisbursementStatus}. */
public enum DisbursementStatus {

    SUCCESS(LoanDisbursementStatus.SUCCESS),
    /** InnBucks definitively refused (or the call provably never left): nothing was paid. */
    FAILED(LoanDisbursementStatus.FAILED),
    /** The call may have paid — a timeout, a 5xx, an unreadable answer. Stays PENDING (in doubt). */
    UNKNOWN(LoanDisbursementStatus.PENDING);

    private final LoanDisbursementStatus loanDisbursementStatus;

    DisbursementStatus(LoanDisbursementStatus loanDisbursementStatus) {
        this.loanDisbursementStatus = loanDisbursementStatus;
    }

    public LoanDisbursementStatus getLoanDisbursementStatus() {
        return loanDisbursementStatus;
    }
}
