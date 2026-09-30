package zw.co.innbucks.loans.core.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkItemRepository extends JpaRepository<WorkItem, Long> {

    Optional<WorkItem> findByStageCodeAndLoanIdAndEnteredAt(String stageCode, Long loanId, LocalDateTime enteredAt);

    /** The stage's items for these loans, whichever of their waits they belong to. */
    List<WorkItem> findByStageCodeAndLoanIdIn(String stageCode, Collection<Long> loanIds);

    List<WorkItem> findByAssignedTo(String assignedTo);

    List<WorkItem> findByLoanIdOrderByIdAsc(Long loanId);
}
