package zw.co.innbucks.loans.core.staff;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StaffRegisterRowRepository extends JpaRepository<StaffRegisterRow, Long> {

    List<StaffRegisterRow> findByBatchIdAndOutcomeOrderByRowNumber(Long batchId, StaffRegisterRowOutcome outcome);

    /** The batches still waiting for approval that would put a staff member at the grade. */
    @Query("select distinct r.batchId from StaffRegisterRow r, StaffRegisterBatch b where b.id = r.batchId"
            + " and b.status = zw.co.innbucks.loans.core.staff.StaffRegisterBatchStatus.PENDING"
            + " and r.outcome = zw.co.innbucks.loans.core.staff.StaffRegisterRowOutcome.STAGED and r.grade = :grade"
            + " order by r.batchId")
    List<Long> findPendingBatchesStaging(@Param("grade") String grade);

    Page<StaffRegisterRow> findByBatchIdOrderByRowNumber(Long batchId, Pageable pageable);

    Page<StaffRegisterRow> findByBatchIdAndOutcomeOrderByRowNumber(Long batchId, StaffRegisterRowOutcome outcome,
                                                                   Pageable pageable);
}
