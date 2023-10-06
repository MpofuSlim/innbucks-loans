package zw.co.reikan.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    Optional<Loan> findByEcNumberAndLoanApprovalStatus(String ecNumber, LoanApprovalStatus loanApprovaStatus);

    Optional<Loan> findTopByNationalIdNumberAndLoanApprovalStatusIn(String idNumber, List<LoanApprovalStatus> statuses);

    List<Loan> findByLoanApprovalStatus(LoanApprovalStatus loanApprovaStatus);

    List<Loan> findByLoanApprovalStatusAndDisbursementStatus(LoanApprovalStatus loanApprovaStatus,
                                                             LoanDisbursementStatus disbursementStatus);
}