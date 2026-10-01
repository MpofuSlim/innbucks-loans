package zw.co.innbucks.loans.core.staff.offer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StaffOfferRunRepository extends JpaRepository<StaffOfferRun, Long> {

    Optional<StaffOfferRun> findFirstByOrderByIdDesc();

    /**
     * Takes the transaction-scoped lock that keeps two runs from overlapping, without waiting: false when another run
     * holds it.
     */
    @Query(value = "select pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryLock(@Param("key") long key);
}
