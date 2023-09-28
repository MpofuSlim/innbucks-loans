package zw.co.reikan.nanoloansweb.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import zw.co.reikan.nanoloansweb.disbursements.LoanDisbursementStatus;

import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    Optional<Loan> findByEcNumberAndLoanApprovalStatus(String ecNumber, LoanApprovalStatus loanApprovaStatus);

    List<Loan> findByLoanApprovalStatus(LoanApprovalStatus loanApprovaStatus);

    List<Loan> findByLoanApprovalStatusAndDisbursementStatus(LoanApprovalStatus loanApprovaStatus,
                                                            LoanDisbursementStatus disbursementStatus);
}