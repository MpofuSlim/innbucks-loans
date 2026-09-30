package zw.co.innbucks.loans.core.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowStageRepository extends JpaRepository<WorkflowStage, String> {

    /** Every stage, in the order the pipeline reaches them. */
    List<WorkflowStage> findAllByOrderByDisplayOrderAsc();
}
