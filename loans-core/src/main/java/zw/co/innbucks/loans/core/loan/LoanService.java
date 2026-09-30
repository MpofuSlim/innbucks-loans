package zw.co.innbucks.loans.core.loan;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import zw.co.innbucks.loans.core.instrument.InstrumentPreview;
import zw.co.innbucks.loans.core.instrument.SigningContext;
import zw.co.innbucks.loans.core.user.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoanService {

    Optional<Loan> findByReference(String reference);

    /**
     * @throws zw.co.innbucks.loans.core.exception.PendingApplicationException when the applicant
     *         already has a loan in flight; nothing is created
     */
    LoanApplicationResponse requestLoan(LoanApplicationRequest request, SigningContext signing);

    /** The instruments the application would sign today, filled with its terms, for the applicant to read. */
    List<InstrumentPreview> previewInstruments(LoanApplicationRequest request);

    /** Prices the terms; {@code originator} decides the commission split and may be null for a plain quote. */
    LoanQuote calculate(LoanQuoteRequest request, User originator);

    /** The originator's disbursed loans between the two market days, both inclusive. */
    SalesSummaryResponse getSalesSummary(Long userId, LocalDate fromDate, LocalDate toDate);

    /**
     * One page of the loans matching the criteria that the caller may read, newest first.
     * A merchant filter outside the caller's scope matches nothing rather than widening it.
     */
    Page<LoanSummaryResponse> findLoans(LoanSearchCriteria criteria, LoanReadScope scope, Pageable pageable);

    /**
     * @throws zw.co.innbucks.loans.core.exception.NotFoundException when no loan has this id
     *         OR it lies outside the scope — deliberately indistinguishable
     */
    LoanResponse getLoan(Long id, LoanReadScope scope);
}
