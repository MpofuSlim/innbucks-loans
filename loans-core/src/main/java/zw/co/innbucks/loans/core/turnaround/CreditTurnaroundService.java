package zw.co.innbucks.loans.core.turnaround;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaroundReportResponse.ActionTurnaround;
import zw.co.innbucks.loans.core.workflow.StageQueue;
import zw.co.innbucks.loans.core.workflow.StageQueue.Visit;
import zw.co.innbucks.loans.core.workflow.StageQueue.Waiting;
import zw.co.innbucks.loans.core.workflow.StageQueues;
import zw.co.innbucks.loans.core.workflow.SystemStage;
import zw.co.innbucks.loans.core.workflow.WaitHours;
import zw.co.innbucks.loans.core.workflow.WorkItem;
import zw.co.innbucks.loans.core.workflow.WorkQueueService;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;
import zw.co.innbucks.loans.core.workflow.WorkflowStageService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Credit's turnaround against the CREDIT_DECISION stage's service level (FR-SSB-015 / FR-PBL-030): every decision made
 * in a period, timed from when its loan reached Credit, and the credit queue as it stands. Escalation is the
 * workflow's, for every stage alike.
 */
@Service
@RequiredArgsConstructor
public class CreditTurnaroundService {

    /** The decisions a wait ends in, in the order they are reported. */
    private static final EnumSet<CreditAction> DECISIONS =
            EnumSet.of(CreditAction.APPROVED, CreditAction.REJECTED, CreditAction.RETURNED);

    private final WorkflowStageService workflowStageService;
    private final StageQueues stageQueues;
    private final WorkQueueService workQueueService;
    private final MarketTimeZone marketTimeZone;

    /**
     * Times every credit decision made in the period (the market's days, both inclusive; this month so far by
     * default), from when its loan reached Credit, against the current service level; and counts the queue now.
     *
     * @throws ValidationException fromDate is after toDate
     */
    @Transactional(readOnly = true)
    public CreditTurnaroundReportResponse report(LocalDate fromDate, LocalDate toDate) {
        LocalDate today = marketTimeZone.today();
        LocalDate from = fromDate == null ? today.withDayOfMonth(1) : fromDate;
        LocalDate to = toDate == null ? today : toDate;
        if (from.isAfter(to)) {
            throw new ValidationException("fromDate must not be after toDate");
        }
        WorkflowStage stage = workflowStageService.stage(SystemStage.CREDIT_DECISION.name());
        StageQueue queue = stageQueues.of(SystemStage.CREDIT_DECISION);

        List<BigDecimal> hours = new ArrayList<>();
        Map<CreditAction, List<BigDecimal>> hoursByAction = new EnumMap<>(CreditAction.class);
        long unmeasured = 0;
        for (Visit visit : queue.endedBetween(marketTimeZone.startOfDayUtc(from), marketTimeZone.endOfDayUtc(to))) {
            if (visit.enteredAt() == null) {
                unmeasured++;
                continue;
            }
            BigDecimal took = WaitHours.between(visit.enteredAt(), visit.endedAt());
            hours.add(took);
            hoursByAction.computeIfAbsent(CreditAction.valueOf(visit.outcome()), action -> new ArrayList<>()).add(took);
        }
        List<ActionTurnaround> byAction = DECISIONS.stream()
                .filter(hoursByAction::containsKey)
                .map(action -> {
                    List<BigDecimal> taken = hoursByAction.get(action);
                    return new ActionTurnaround(action, taken.size(), WaitHours.within(taken, stage.getTargetHours()),
                            WaitHours.average(taken));
                })
                .toList();
        long within = WaitHours.within(hours, stage.getTargetHours());

        List<Waiting> waiting = queue.waiting();
        Map<Long, WorkItem> items = workQueueService.currentItems(stage.getCode(), waiting);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new CreditTurnaroundReportResponse(from, to, stage.getTargetHours(), stage.getEscalationHours(),
                hours.size(), within, WaitHours.percent(within, hours.size()), WaitHours.average(hours),
                WaitHours.median(hours), WaitHours.longest(hours), unmeasured, byAction, waiting.size(),
                waiting.stream().filter(wait -> WorkQueueService.overdue(stage, wait.enteredAt(), now)).count(),
                items.values().stream().filter(item -> item.getEscalatedAt() != null).count());
    }
}
