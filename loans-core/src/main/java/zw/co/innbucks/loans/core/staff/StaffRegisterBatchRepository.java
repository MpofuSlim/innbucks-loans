package zw.co.innbucks.loans.core.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StaffRegisterBatchRepository extends JpaRepository<StaffRegisterBatch, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from StaffRegisterBatch b where b.id = :id")
    Optional<StaffRegisterBatch> findByIdForUpdate(@Param("id") Long id);

    Page<StaffRegisterBatch> findByStatus(StaffRegisterBatchStatus status, Pageable pageable);
}
