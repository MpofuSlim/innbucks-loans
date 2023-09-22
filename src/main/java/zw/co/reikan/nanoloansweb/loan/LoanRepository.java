package zw.co.reikan.nanoloansweb.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import zw.co.reikan.nanoloansweb.disbursements.LoanDisbursementStatus;

import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    Optional<Loan> findByEcNumberAndLoanApprovaStatus(String ecNumber, LoanApprovaStatus loanApprovaStatus);

    List<Loan> findByLoanApprovaStatus(LoanApprovaStatus loanApprovaStatus);

    List<Loan> findByLoanApprovaStatusAndDisbursementStatus(LoanApprovaStatus loanApprovaStatus,
                                                            LoanDisbursementStatus disbursementStatus);
}