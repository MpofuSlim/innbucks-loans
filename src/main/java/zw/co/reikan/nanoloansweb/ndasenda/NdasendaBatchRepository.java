package zw.co.reikan.nanoloansweb.ndasenda;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NdasendaBatchRepository extends JpaRepository<NdasendaBatch, Long> {
    Optional<NdasendaBatch> findByDeductionBatchStatus(DeductionBatchStatus status);
}
