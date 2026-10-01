package zw.co.innbucks.loans.core.staff.offer;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * The weekly offer run's settings and whether a run would go ahead now.
 *
 * @param runCron                  when the run fires, read on the market's clock ({@code zone}); only where the
 *                                 scheduled-tasks profile is on
 * @param nextScheduledRunAt       the schedule's next firing
 * @param lastReconciliationId     the latest payroll reconciliation, absent when there has been none
 * @param reconciliationAgeDays    how many market days ago it ran
 * @param readyToRun               whether a run now would go ahead; when not, {@code notReadyReason} says why
 * @param lastRun                  the latest attempt at a run, absent before the first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffOfferScheduleResponse(
        String runCron,
        String zone,
        LocalDateTime nextScheduledRunAt,
        int validityDays,
        int maxReconciliationAgeDays,
        Long lastReconciliationId,
        LocalDateTime lastReconciledAt,
        Long reconciliationAgeDays,
        boolean readyToRun,
        String notReadyReason,
        StaffOfferRunResponse lastRun) {
}
