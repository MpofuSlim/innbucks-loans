package zw.co.reikan.nanoloansweb.loan;

import zw.co.reikan.nanoloansweb.disbursements.LoanDisbursementStatus;

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
