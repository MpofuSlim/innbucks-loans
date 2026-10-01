package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An approved grade limit (FR-SGL-009): what the matrix says for a grade from {@code effectiveFrom}.
 *
 * @param changeId the approved change it comes from
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffGradeLimit(
        Long changeId,
        String grade,
        String scoreBand,
        BigDecimal maximumLimit,
        LocalDate effectiveFrom,
        String approvedBy,
        LocalDateTime approvedAt) {

    static StaffGradeLimit of(StaffGradeLimitChange change) {
        return new StaffGradeLimit(change.getId(), change.getGrade(), change.getScoreBand(), change.getMaximumLimit(),
                change.getEffectiveFrom(), change.getDecidedBy(), change.getDecidedAt());
    }

    /** Whether the grade is lent to at all: a limit of zero stops lending to it. */
    public boolean lends() {
        return maximumLimit != null && maximumLimit.signum() > 0;
    }
}
