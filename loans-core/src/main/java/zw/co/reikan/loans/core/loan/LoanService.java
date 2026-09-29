package zw.co.reikan.loans.core.loan;

import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.api.FindLoansInternalRequest;
import zw.co.reikan.loans.core.api.FindLoansRequest;
import zw.co.reikan.loans.core.api.LoanStatisticsResponse;
import zw.co.reikan.loans.core.user.User;

import java.util.List;
import java.util.Optional;

public interface LoanService {
    Optional<Loan> findByReference(String reference);

    LoanResponse requestLoan(LoanRequest loanRequest);

    LoanDetails calculate(LoanRequest request, User loggedInUser);

    LoanStatisticsResponse getStatistics(FindLoansInternalRequest request);

    List<LoanDto> findLoans(FindLoansRequest findLoansRequest);

    List<LoanDto> findLoansForMerchant(FindLoansInternalRequest request);

    /** {@link #findLoans} narrowed to what the caller may read. */
    List<LoanDto> findLoans(FindLoansRequest findLoansRequest, LoanReadScope scope);

    /** @throws zw.co.reikan.loans.core.exception.NotFoundException when no loan has this id */
    LoanDto getLoan(Long id);

    /**
     * @throws zw.co.reikan.loans.core.exception.NotFoundException when no loan has this id
     *         OR it lies outside the scope — deliberately indistinguishable
     */
    LoanDto getLoan(Long id, LoanReadScope scope);
}
