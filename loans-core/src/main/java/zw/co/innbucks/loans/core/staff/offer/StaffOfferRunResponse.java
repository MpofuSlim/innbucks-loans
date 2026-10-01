package zw.co.innbucks.loans.core.staff.offer;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One attempt at the weekly offer run and how it ended. The counts are on a COMPLETED run only, and add up:
 * {@code registerMembers = ineligible + excludedActiveLoan + excludedArrears + excludedByReconciliation + eligible},
 * and {@code eligible = offered + refreshed + alreadyOffered}.
 *
 * @param cycleStart               the Monday of the market week the run belongs to; one offer per member per cycle
 * @param reason                   why it was REFUSED or FAILED
 * @param reconciliationId         the payroll reconciliation it relied on, or, when REFUSED, found too old
 * @param ineligible               members who may not borrow: not ACTIVE, or their grade has no limit above zero today
 * @param excludedActiveLoan       eligible members holding an active Staff Grocery Loan
 * @param excludedArrears          eligible members in arrears on one
 * @param excludedByReconciliation eligible members the latest payroll reconciliation lists as having left or not on
 *                                 the payroll, whose register record has not changed since
 * @param offered                  members given an offer who held no open one
 * @param refreshed                members whose open offer was replaced by a new one
 * @param alreadyOffered           members who already held this cycle's offer: a repeated run gives them nothing
 * @param withdrawn                open offers taken back because their holder is no longer eligible or is excluded
 * @param expired                  offers that had lapsed unaccepted, closed by this run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffOfferRunResponse(
        Long id,
        LocalDate cycleStart,
        StaffOfferRunTrigger trigger,
        String startedBy,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        StaffOfferRunStatus status,
        String reason,
        Long reconciliationId,
        Integer registerMembers,
        Integer ineligible,
        Integer excludedActiveLoan,
        Integer excludedArrears,
        Integer excludedByReconciliation,
        Integer eligible,
        Integer offered,
        Integer refreshed,
        Integer alreadyOffered,
        Integer withdrawn,
        Integer expired) {

    static StaffOfferRunResponse of(StaffOfferRun run) {
        return new StaffOfferRunResponse(run.getId(), run.getCycleStart(), run.getTrigger(), run.getStartedBy(),
                run.getStartedAt(), run.getFinishedAt(), run.getStatus(), run.getReason(), run.getReconciliationId(),
                run.getRegisterMembers(), run.getIneligible(), run.getExcludedActiveLoan(), run.getExcludedArrears(),
                run.getExcludedByReconciliation(), run.getEligible(), run.getOffered(), run.getRefreshed(),
                run.getAlreadyOffered(), run.getWithdrawn(), run.getExpired());
    }
}
