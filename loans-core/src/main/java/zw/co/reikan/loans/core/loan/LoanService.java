package zw.co.reikan.loans.core.loan;

import zw.co.reikan.loans.core.LoanResponse;

public interface LoanService {
    LoanResponse requestLoan(LoanRequest loanRequest);

    LoanDetails calculate(LoanRequest request);
}
