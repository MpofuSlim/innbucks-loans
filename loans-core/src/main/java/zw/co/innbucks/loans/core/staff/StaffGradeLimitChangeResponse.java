package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A change to the grade-to-limit matrix, as proposed and decided.
 *
 * @param replacing    on a PENDING change only: the approved limit the grade would otherwise have on
 *                     {@code effectiveFrom}, so the checker sees what changes; absent for a grade with none
 * @param supersededBy on a SUPERSEDED change: the approved change that replaced it
 * @param retiredBy    on a RETIRED change: the grade change that retired or renamed its grade
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffGradeLimitChangeResponse(
        Long id,
        String grade,
        String scoreBand,
        BigDecimal maximumLimit,
        LocalDate effectiveFrom,
        StaffGradeLimitChangeStatus status,
        String proposedBy,
        LocalDateTime proposedAt,
        String comment,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionComment,
        Long supersededBy,
        Long retiredBy,
        StaffGradeLimit replacing) {

    static StaffGradeLimitChangeResponse of(StaffGradeLimitChange change, StaffGradeLimit replacing) {
        return new StaffGradeLimitChangeResponse(change.getId(), change.getGrade(), change.getScoreBand(),
                change.getMaximumLimit(), change.getEffectiveFrom(), change.getStatus(), change.getProposedBy(),
                change.getProposedAt(), change.getProposalComment(), change.getDecidedBy(), change.getDecidedAt(),
                change.getDecisionComment(), change.getSupersededBy(), change.getRetiredBy(), replacing);
    }
}
