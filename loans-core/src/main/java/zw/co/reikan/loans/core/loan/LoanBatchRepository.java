package zw.co.reikan.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LoanBatchRepository extends JpaRepository<LoanBatch, Long> {
    Optional<LoanBatch> findByBatchNumber(String batchNumber);
    Boolean existsByBatchNumber(String batchNumber);
}