package zw.co.reikan.nanoloansweb.loan;

public enum SsbStatus {

    APPROVED(LoanApprovaStatus.APPROVED), REJECTED(LoanApprovaStatus.REJECTED);

    private final LoanApprovaStatus loanApprovaStatus;

    SsbStatus(LoanApprovaStatus loanApprovaStatus) {
        this.loanApprovaStatus = loanApprovaStatus;
    }

    public LoanApprovaStatus getLoanStatus() {
        return loanApprovaStatus;
    }
}
