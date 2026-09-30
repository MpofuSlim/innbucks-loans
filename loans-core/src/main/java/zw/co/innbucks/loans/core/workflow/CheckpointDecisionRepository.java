package zw.co.innbucks.loans.core.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface CheckpointDecisionRepository extends JpaRepository<CheckpointDecision, Long> {

    boolean existsByStageCodeAndLoanId(String stageCode, Long loanId);

    /** The decisions at these checkpoints on these loans. */
    List<CheckpointDecision> findByStageCodeInAndLoanIdIn(Collection<String> stageCodes, Collection<Long> loanIds);

    /** Every checkpoint decision on the loan, oldest first. */
    List<CheckpointDecision> findByLoanIdOrderByIdAsc(Long loanId);

    /** The checkpoint's decisions made in the period, oldest first. */
    List<CheckpointDecision> findByStageCodeAndDecidedAtBetweenOrderByIdAsc(String stageCode, LocalDateTime from,
                                                                            LocalDateTime to);
}
