package zw.co.innbucks.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import zw.co.innbucks.loans.core.dashboard.DashboardEntityCounts;

import java.util.Optional;

@Repository
public interface LoanBatchRepository extends JpaRepository<LoanBatch, Long> {
    Optional<LoanBatch> findByBatchNumber(String batchNumber);
    Boolean existsByBatchNumber(String batchNumber);

    /** The admin dashboard's merchant, user and batch counts in one statement, not three. */
    @Query("""
            select new zw.co.innbucks.loans.core.dashboard.DashboardEntityCounts(
                       (select count(m) from Merchant m), (select count(u) from User u), (select count(b) from LoanBatch b))
            """)
    DashboardEntityCounts dashboardEntityCounts();
}
