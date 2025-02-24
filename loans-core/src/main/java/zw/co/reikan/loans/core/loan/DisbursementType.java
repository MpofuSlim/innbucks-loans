package zw.co.reikan.loans.core.loan;

public enum DisbursementType {

    CUSTOMER_MOBILE_WALLET(LoanType.PERSONAL), MERCHANT_MOBILE_WALLET(LoanType.CONSUMER_FINANCE);

    private final LoanType loanType;

    DisbursementType(LoanType loanType) {
        this.loanType = loanType;
    }
    
    public LoanType getLoanType() {
        return loanType;
    }
}
