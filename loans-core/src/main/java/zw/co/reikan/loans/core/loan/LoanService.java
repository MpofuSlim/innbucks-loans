package zw.co.reikan.loans.core.loan;

import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.api.FindLoansInternalRequest;
import zw.co.reikan.loans.core.api.FindLoansRequest;
import zw.co.reikan.loans.core.api.LoanStatisticsResponse;
import zw.co.reikan.loans.core.user.User;

import java.util.List;

public interface LoanService {
    LoanResponse requestLoan(LoanRequest loanRequest);

    LoanDetails calculate(LoanRequest request, User loggedInUser);

    LoanStatisticsResponse getStatistics(FindLoansInternalRequest request);

    List<LoanDto> findLoans(FindLoansRequest findLoansRequest);

    List<LoanDto> findLoansForMerchant(FindLoansInternalRequest request);

    LoanDto getLoan(Long id);
}
