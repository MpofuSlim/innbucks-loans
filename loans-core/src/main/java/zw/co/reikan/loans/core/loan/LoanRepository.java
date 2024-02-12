package zw.co.reikan.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LoanRepository extends JpaRepository<Loan, Long>, JpaSpecificationExecutor<Loan> {

    Optional<Loan> findByEcNumberAndLoanApprovalStatus(String ecNumber, LoanApprovalStatus loanApprovaStatus);

    Optional<Loan> findTopByNationalIdNumberAndLoanApprovalStatusIn(String idNumber, List<LoanApprovalStatus> statuses);

    List<Loan> findByLoanApprovalStatus(LoanApprovalStatus loanApprovaStatus);

    List<Loan> findByLoanAccountStatusAndDisbursementStatus(LoanAccountStatus loanAccountStatus,
                                                            LoanDisbursementStatus disbursementStatus);

    List<Loan> findByLoanApprovalStatusAndLoanAccountStatus(LoanApprovalStatus loanApprovaStatus,
                                                            LoanAccountStatus loanAccountStatus);

    List<Loan> findByCreatedDateBetween(LocalDateTime startDate, LocalDateTime endDate);

}