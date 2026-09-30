package zw.co.innbucks.loans.core.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface WorkItemEventRepository extends JpaRepository<WorkItemEvent, Long> {

    List<WorkItemEvent> findByWorkItemIdInOrderByIdAsc(Collection<Long> workItemIds);
}
