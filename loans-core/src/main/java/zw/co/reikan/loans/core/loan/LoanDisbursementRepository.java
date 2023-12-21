package zw.co.reikan.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanDisbursementRepository extends JpaRepository<LoanDisbursement, Long> {
}