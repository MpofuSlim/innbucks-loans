package zw.co.innbucks.loans.core.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A stage as configured. The role lists are who holds each entitlement in effect: SUPER_ADMIN always, and working
 * or assigning includes seeing. A stage whose items are not assigned (MORE_INFORMATION, worked by each application's
 * originator) has no work or assign roles.
 *
 * @param escalationHours absent when the stage is never escalated
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowStageResponse(
        String code,
        StageKind kind,
        String name,
        String description,
        int displayOrder,
        AssignmentMode assignment,
        List<UserGroup> viewRoles,
        List<UserGroup> workRoles,
        List<UserGroup> assignRoles,
        int targetHours,
        Integer escalationHours,
        List<UserGroup> escalateTo,
        boolean notifyAssignee,
        String updatedBy,
        LocalDateTime updatedAt) {

    static WorkflowStageResponse of(WorkflowStage stage) {
        boolean assignable = SystemStage.of(stage.getCode()).map(SystemStage::assignable).orElse(true);
        Set<UserGroup> view = withSuperAdmin(stage.rolesWith(Entitlement.VIEW));
        view.addAll(stage.rolesWith(Entitlement.WORK));
        view.addAll(stage.rolesWith(Entitlement.ASSIGN));
        return new WorkflowStageResponse(stage.getCode(), stage.getKind(), stage.getName(), stage.getDescription(),
                stage.getDisplayOrder(), stage.getAssignment(), List.copyOf(view),
                assignable ? List.copyOf(withSuperAdmin(stage.rolesWith(Entitlement.WORK))) : List.of(),
                assignable ? List.copyOf(withSuperAdmin(stage.rolesWith(Entitlement.ASSIGN))) : List.of(),
                stage.getTargetHours(), stage.getEscalationHours(),
                List.copyOf(stage.getEscalationRoles().isEmpty() ? EnumSet.noneOf(UserGroup.class)
                        : EnumSet.copyOf(stage.getEscalationRoles())),
                stage.isNotifyAssignee(), stage.getUpdatedBy(), stage.getUpdatedAt());
    }

    private static Set<UserGroup> withSuperAdmin(Set<UserGroup> roles) {
        Set<UserGroup> effective = EnumSet.of(UserGroup.SUPER_ADMIN);
        effective.addAll(roles);
        return effective;
    }
}
