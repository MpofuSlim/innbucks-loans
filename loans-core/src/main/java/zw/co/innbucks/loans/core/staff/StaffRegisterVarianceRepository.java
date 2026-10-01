package zw.co.innbucks.loans.core.staff;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Variances come back in the order they were written: most urgent kind first, then as the report sorted them. */
public interface StaffRegisterVarianceRepository extends JpaRepository<StaffRegisterVariance, Long> {

    Page<StaffRegisterVariance> findByReconciliationIdOrderById(Long reconciliationId, Pageable pageable);

    Page<StaffRegisterVariance> findByReconciliationIdAndKindOrderById(Long reconciliationId,
                                                                       StaffRegisterVarianceKind kind,
                                                                       Pageable pageable);

    @Query("select v.employeeNumber from StaffRegisterVariance v"
            + " where v.reconciliationId = :reconciliationId and v.kind in :kinds")
    List<String> findEmployeeNumbers(@Param("reconciliationId") Long reconciliationId,
                                     @Param("kinds") Collection<StaffRegisterVarianceKind> kinds);
}
