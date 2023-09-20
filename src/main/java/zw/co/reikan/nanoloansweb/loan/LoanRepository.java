package zw.co.reikan.nanoloansweb.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import zw.co.reikan.nanoloansweb.disbursements.LoanDisbursementStatus;

import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {
    Optional<Loan> findByEcNumberAndLoanStatus(String ecNumber, LoanStatus loanStatus);

    List<Loan> findByLoanStatus(LoanStatus loanStatus);

    List<Loan> findByLoanStatusAndDisbursementStatus(LoanStatus loanStatus, LoanDisbursementStatus disbursementStatus);
}