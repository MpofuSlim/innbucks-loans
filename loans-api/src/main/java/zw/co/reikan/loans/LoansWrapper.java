package zw.co.reikan.loans;

import zw.co.reikan.loans.core.loan.LoanDto;

import java.util.List;

public class LoansWrapper {
    private List<LoanDto> loans;

    public LoansWrapper(List<LoanDto> loans) {
        this.loans = loans;
    }
}
