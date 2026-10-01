package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * A proposal to retire or rename a grade, as proposed and decided.
 *
 * @param newGrade            for a RENAME: the new name
 * @param staffMembers        on a PENDING change only: how many staff members hold the grade now, so the checker sees
 *                            who a rename moves (a retirement is refused while any still employed holds it)
 * @param staffMembersMoved   for an approved RENAME: the staff members moved to the new name
 * @param limitOverridesMoved for an approved RENAME: the pending and approved limit overrides moved with them
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffGradeChangeResponse(
        Long id,
        StaffGradeChangeAction action,
        String grade,
        String newGrade,
        StaffGradeChangeStatus status,
        String proposedBy,
        LocalDateTime proposedAt,
        String comment,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionComment,
        Long staffMembers,
        Integer staffMembersMoved,
        Integer limitOverridesMoved) {

    static StaffGradeChangeResponse of(StaffGradeChange change, Long staffMembers) {
        return new StaffGradeChangeResponse(change.getId(), change.getAction(), change.getGrade(), change.getNewGrade(),
                change.getStatus(), change.getProposedBy(), change.getProposedAt(), change.getProposalComment(),
                change.getDecidedBy(), change.getDecidedAt(), change.getDecisionComment(), staffMembers,
                change.getStaffMembersMoved(), change.getLimitOverridesMoved());
    }
}
