package zw.co.reikan.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoanDisbursementRepository extends JpaRepository<LoanDisbursement, Long> {

    /** Every manual payout attempt made for the loan — the history a new attempt is judged against. */
    List<LoanDisbursement> findByLoanId(Long loanId);

    /** Whether any manual payout was ever attempted for the loan. */
    boolean existsByLoanId(Long loanId);
}
