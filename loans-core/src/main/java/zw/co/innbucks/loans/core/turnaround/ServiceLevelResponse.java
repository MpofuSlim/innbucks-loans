package zw.co.innbucks.loans.core.turnaround;

import java.time.LocalDateTime;

/** A stage's service level (FR-PBL-030). */
public record ServiceLevelResponse(
        ServiceLevelStage stage,
        int targetHours,
        int escalationHours,
        String updatedBy,
        LocalDateTime updatedAt) {

    static ServiceLevelResponse of(ServiceLevel level) {
        return new ServiceLevelResponse(level.getStage(), level.getTargetHours(), level.getEscalationHours(),
                level.getUpdatedBy(), level.getUpdatedAt());
    }
}
