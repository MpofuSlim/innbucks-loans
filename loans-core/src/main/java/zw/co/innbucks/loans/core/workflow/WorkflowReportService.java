package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.workflow.StageQueue.Visit;
import zw.co.innbucks.loans.core.workflow.StageQueue.Waiting;
import zw.co.innbucks.loans.core.workflow.WorkflowPipelineReportResponse.Breakdown;
import zw.co.innbucks.loans.core.workflow.WorkflowPipelineReportResponse.StagePipeline;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The workflow pipeline report (FR-SSB-014, BRD 7.4): each stage's queue now, and adherence to its current target
 * over the waits that ended in the period, overall and by channel and originating agent.
 */
@Service
@RequiredArgsConstructor
public class WorkflowReportService {

    static final String PORTAL = WorkflowStage.PORTAL;
    private static final String PORTAL_NAME = "Portal";

    private final WorkflowStageRepository workflowStageRepository;
    private final StageQueues stageQueues;
    private final WorkQueueService workQueueService;
    private final LoanRepository loanRepository;
    private final MarketTimeZone marketTimeZone;

    /**
     * The market's days, both inclusive; this month so far by default.
     *
     * @throws ValidationException fromDate is after toDate
     */
    @Transactional(readOnly = true)
    public WorkflowPipelineReportResponse pipeline(LocalDate fromDate, LocalDate toDate) {
        LocalDate today = marketTimeZone.today();
        LocalDate from = fromDate == null ? today.withDayOfMonth(1) : fromDate;
        LocalDate to = toDate == null ? today : toDate;
        if (from.isAfter(to)) {
            throw new ValidationException("fromDate must not be after toDate");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<StagePipeline> stages = new ArrayList<>();
        for (WorkflowStage stage : workflowStageRepository.findAllByOrderByDisplayOrderAscCodeAsc()) {
            StageQueue queue = stageQueues.of(stage);
            List<Visit> visits = queue.endedBetween(marketTimeZone.startOfDayUtc(from), marketTimeZone.endOfDayUtc(to));
            if (!stage.isActive() && visits.isEmpty()) {
                // A checkpoint no longer in use, with nothing to report for the period.
                continue;
            }
            stages.add(stagePipeline(stage, queue.waiting(), visits, now));
        }
        return new WorkflowPipelineReportResponse(from, to, stages);
    }

    private StagePipeline stagePipeline(WorkflowStage stage, List<Waiting> waiting, List<Visit> visits,
                                        LocalDateTime now) {
        Map<Long, WorkItem> items = workQueueService.currentItems(stage.getCode(), waiting);
        Map<Long, Loan> visitLoans = visits.isEmpty() ? Map.of() : loanRepository
                .findAllById(visits.stream().map(Visit::loanId).distinct().toList()).stream()
                .collect(Collectors.toMap(Loan::getId, Function.identity()));

        Tally total = new Tally(null, null);
        Map<String, Tally> byChannel = new LinkedHashMap<>();
        Map<String, Tally> byOriginator = new LinkedHashMap<>();
        for (Waiting wait : waiting) {
            boolean overdue = WorkQueueService.overdue(stage, wait.enteredAt(), now);
            for (Tally tally : tallies(wait.loan(), total, byChannel, byOriginator)) {
                tally.waiting++;
                tally.overdue += overdue ? 1 : 0;
            }
        }
        long unmeasured = 0;
        for (Visit visit : visits) {
            if (visit.enteredAt() == null) {
                unmeasured++;
                continue;
            }
            BigDecimal took = WaitHours.between(visit.enteredAt(), visit.endedAt());
            Loan loan = visitLoans.get(visit.loanId());
            for (Tally tally : loan == null ? List.of(total) : tallies(loan, total, byChannel, byOriginator)) {
                tally.hours.add(took);
            }
        }
        boolean assigned = WorkQueueService.assigns(stage);
        return new StagePipeline(stage.getCode(), stage.getName(), stage.getTargetHours(), stage.getEscalationHours(),
                total.waiting, total.overdue,
                items.values().stream().filter(item -> item.getEscalatedAt() != null).count(),
                assigned ? waiting.stream().filter(wait -> {
                    WorkItem item = items.get(wait.loan().getId());
                    return item == null || item.getAssignedTo() == null;
                }).count() : null,
                total.hours.size(), WaitHours.within(total.hours, stage.getTargetHours()),
                WaitHours.percent(WaitHours.within(total.hours, stage.getTargetHours()), total.hours.size()),
                WaitHours.average(total.hours), WaitHours.median(total.hours), WaitHours.longest(total.hours),
                unmeasured, breakdowns(byChannel, stage), breakdowns(byOriginator, stage));
    }

    private static List<Tally> tallies(Loan loan, Tally total, Map<String, Tally> byChannel,
                                       Map<String, Tally> byOriginator) {
        Channel channel = loan.getChannel();
        String channelKey = channel == null ? PORTAL : channel.getChannelId();
        Tally channelTally = byChannel.computeIfAbsent(channelKey,
                key -> new Tally(key, channel == null ? PORTAL_NAME : channel.getName()));
        String originator = Objects.requireNonNullElse(loan.getCreatedBy(), "unknown");
        User originatorUser = loan.getCreatedByUser();
        Tally originatorTally = byOriginator.computeIfAbsent(originator,
                key -> new Tally(key, originatorUser == null ? null : originatorUser.fullName()));
        return List.of(total, channelTally, originatorTally);
    }

    private static List<Breakdown> breakdowns(Map<String, Tally> tallies, WorkflowStage stage) {
        return tallies.values().stream()
                .sorted(Comparator.comparing((Tally tally) -> tally.key))
                .map(tally -> {
                    long within = WaitHours.within(tally.hours, stage.getTargetHours());
                    return new Breakdown(tally.key, tally.name, tally.waiting, tally.overdue, tally.hours.size(),
                            within, WaitHours.percent(within, tally.hours.size()), WaitHours.average(tally.hours));
                })
                .toList();
    }

    /** What waits, and how long the ended waits took, for one stage, channel or originator. */
    private static final class Tally {
        private final String key;
        private final String name;
        private final List<BigDecimal> hours = new ArrayList<>();
        private long waiting;
        private long overdue;

        private Tally(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }
}
