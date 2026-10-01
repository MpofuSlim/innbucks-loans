package zw.co.innbucks.loans.core.staff;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaffRegisterRowRepository extends JpaRepository<StaffRegisterRow, Long> {

    List<StaffRegisterRow> findByBatchIdAndOutcomeOrderByRowNumber(Long batchId, StaffRegisterRowOutcome outcome);

    Page<StaffRegisterRow> findByBatchIdOrderByRowNumber(Long batchId, Pageable pageable);

    Page<StaffRegisterRow> findByBatchIdAndOutcomeOrderByRowNumber(Long batchId, StaffRegisterRowOutcome outcome,
                                                                   Pageable pageable);
}
