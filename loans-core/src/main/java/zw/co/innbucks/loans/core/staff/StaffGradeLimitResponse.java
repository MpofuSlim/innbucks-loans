package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One grade in the matrix on a day.
 *
 * @param current        the limit in force that day; absent while the grade's first limit is still to come
 * @param scheduled      approved limits from later days, soonest first
 * @param pendingChanges proposals for the grade waiting for a checker
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffGradeLimitResponse(
        String grade,
        StaffGradeLimit current,
        List<StaffGradeLimit> scheduled,
        int pendingChanges) {
}
