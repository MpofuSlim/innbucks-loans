package zw.co.reikan.nanoloansweb.loan;

public enum SsbStatus {

    APPROVED(LoanStatus.APPROVED), REJECTED(LoanStatus.REJECTED);

    private final LoanStatus loanStatus;

    SsbStatus(LoanStatus loanStatus) {
        this.loanStatus = loanStatus;
    }

    public LoanStatus getLoanStatus() {
        return loanStatus;
    }
}
