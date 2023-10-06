package zw.co.reikan.loans.core.loan;

import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

public enum DisbursementStatus {

    SUCCESS(LoanDisbursementStatus.SUCCESS), FAILED(LoanDisbursementStatus.FAILED);

    private final LoanDisbursementStatus loanDisbursementStatus;

    DisbursementStatus(LoanDisbursementStatus loanDisbursementStatus) {
        this.loanDisbursementStatus = loanDisbursementStatus;
    }

    public LoanDisbursementStatus getLoanDisbursementStatus() {
        return loanDisbursementStatus;
    }
}
