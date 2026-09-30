package zw.co.innbucks.loans.core.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowStageRepository extends JpaRepository<WorkflowStage, String> {

    /** Every stage, in the order the pipeline reaches them. */
    List<WorkflowStage> findAllByOrderByDisplayOrderAscCodeAsc();

    /** The active checkpoints holding loans at the point, in display order. */
    List<WorkflowStage> findByKindAndHoldPointAndActiveTrueOrderByDisplayOrderAscCodeAsc(StageKind kind,
                                                                                       HoldPoint holdPoint);

    /** Every checkpoint, active or not, in display order. */
    List<WorkflowStage> findByKindOrderByDisplayOrderAscCodeAsc(StageKind kind);
}
