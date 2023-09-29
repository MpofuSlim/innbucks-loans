package zw.co.reikan.nanoloansweb.loan;

import zw.co.reikan.nanoloansweb.LoanResponse;

public interface LoanService {
    LoanResponse requestLoan(LoanRequest loanRequest);

    LoanDetails calculate(LoanRequest request);
}
