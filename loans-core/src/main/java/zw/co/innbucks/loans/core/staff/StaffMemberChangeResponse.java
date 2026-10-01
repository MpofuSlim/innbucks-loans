package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One field of a staff record changing (FR-SGL-006).
 *
 * @param previousValue absent when the record was created
 * @param batchId       the register batch that made the change; absent when a grade rename did
 * @param gradeChangeId the grade change that renamed the member's grade; absent when a register batch made the change
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffMemberChangeResponse(
        String field,
        String previousValue,
        String newValue,
        Long batchId,
        Long gradeChangeId,
        String submittedBy,
        String approvedBy,
        LocalDateTime changedAt) {

    static StaffMemberChangeResponse of(StaffMemberChange change) {
        return new StaffMemberChangeResponse(change.getField(), change.getPreviousValue(), change.getNewValue(),
                change.getBatchId(), change.getGradeChangeId(), change.getSubmittedBy(), change.getApprovedBy(), change.getChangedAt());
    }
}
