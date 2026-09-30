package zw.co.innbucks.loans.core.turnaround;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.loan.CreditAction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Credit's turnaround over a period, against the service level (FR-PBL-030): every decision made in the period,
 * timed from when its loan reached Credit, and the queue as it stands now.
 *
 * @param decisions        decisions made in the period and timed
 * @param withinTarget     of those, made within the target
 * @param adherencePercent withinTarget as a share of decisions; absent when there were none
 * @param unmeasured       decisions on a loan with no record of reaching Credit (declined before SSB answered), not timed
 * @param awaiting         loans waiting on Credit now
 * @param overdue          of those, past the target now
 * @param escalated        of those, escalated
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreditTurnaroundReportResponse(
        LocalDate fromDate,
        LocalDate toDate,
        int targetHours,
        int escalationHours,
        long decisions,
        long withinTarget,
        BigDecimal adherencePercent,
        BigDecimal averageHours,
        BigDecimal medianHours,
        BigDecimal longestHours,
        long unmeasured,
        List<ActionTurnaround> byAction,
        long awaiting,
        long overdue,
        long escalated) {

    /** One kind of decision: approvals, rejections or returns for more information. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionTurnaround(CreditAction action, long decisions, long withinTarget, BigDecimal averageHours) {
    }
}
