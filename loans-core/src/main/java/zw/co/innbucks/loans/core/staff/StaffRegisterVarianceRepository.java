package zw.co.innbucks.loans.core.staff;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Variances come back in the order they were written: most urgent kind first, then as the report sorted them. */
public interface StaffRegisterVarianceRepository extends JpaRepository<StaffRegisterVariance, Long> {

    Page<StaffRegisterVariance> findByReconciliationIdOrderById(Long reconciliationId, Pageable pageable);

    Page<StaffRegisterVariance> findByReconciliationIdAndKindOrderById(Long reconciliationId,
                                                                       StaffRegisterVarianceKind kind,
                                                                       Pageable pageable);
}
