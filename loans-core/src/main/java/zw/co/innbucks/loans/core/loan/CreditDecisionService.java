package zw.co.innbucks.loans.core.loan;

/** Credit's decision on a loan SSB has accepted (FR-SSB-015): approve it for booking, or reject it. */
public interface CreditDecisionService {

    /**
     * Records the decision and returns the loan as it now stands.
     *
     * @throws zw.co.innbucks.loans.core.exception.NotFoundException no such loan
     * @throws org.springframework.security.access.AccessDeniedException the caller originated the loan and is approving it
     */
    LoanResponse decide(Long loanId, CreditDecisionRequest request);
}
