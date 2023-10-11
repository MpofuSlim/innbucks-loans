package zw.co.reikan.loans.core.loan;

import zw.co.reikan.loans.core.LoanResponse;

import java.time.LocalDate;
import java.util.List;

public interface LoanService {
    LoanResponse requestLoan(LoanRequest loanRequest);

    LoanDetails calculate(LoanRequest request);

    List<LoanDto> findByDateCreated(LocalDate fromDate, LocalDate toDate);
}
