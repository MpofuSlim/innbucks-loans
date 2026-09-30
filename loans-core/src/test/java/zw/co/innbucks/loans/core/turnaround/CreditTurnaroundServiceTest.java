package zw.co.innbucks.loans.core.turnaround;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaroundReportResponse.ActionTurnaround;
import zw.co.innbucks.loans.core.workflow.AssignmentMode;
import zw.co.innbucks.loans.core.workflow.StageKind;
import zw.co.innbucks.loans.core.workflow.StageQueue;
import zw.co.innbucks.loans.core.workflow.StageQueue.Visit;
import zw.co.innbucks.loans.core.workflow.StageQueue.Waiting;
import zw.co.innbucks.loans.core.workflow.StageQueues;
import zw.co.innbucks.loans.core.workflow.SystemStage;
import zw.co.innbucks.loans.core.workflow.WorkItem;
import zw.co.innbucks.loans.core.workflow.WorkQueueService;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;
import zw.co.innbucks.loans.core.workflow.WorkflowStageService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Credit's adherence report against the CREDIT_DECISION stage's service level (FR-SSB-015 / FR-PBL-030). */
class CreditTurnaroundServiceTest {

    /** 10:00 on 30 September in Harare. */
    private static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");

    private StageQueue queue;
    private WorkQueueService workQueueService;
    private CreditTurnaroundService service;

    @BeforeEach
    void setUp() {
        WorkflowStageService workflowStageService = mock(WorkflowStageService.class);
        when(workflowStageService.stage("CREDIT_DECISION")).thenReturn(WorkflowStage.builder()
                .code("CREDIT_DECISION").kind(StageKind.SYSTEM).name("Credit decision")
                .assignment(AssignmentMode.OPTIONAL).targetHours(24).escalationHours(48)
                .updatedBy("system").updatedAt(LocalDateTime.of(2026, 9, 1, 0, 0)).build());
        queue = mock(StageQueue.class);
        StageQueues stageQueues = mock(StageQueues.class);
        when(stageQueues.of(SystemStage.CREDIT_DECISION)).thenReturn(queue);
        workQueueService = mock(WorkQueueService.class);
        when(workQueueService.currentItems(any(), anyList())).thenReturn(Map.of());
        service = new CreditTurnaroundService(workflowStageService, stageQueues, workQueueService,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static Waiting waiting(long id, LocalDateTime since) {
        Loan loan = new Loan();
        loan.setId(id);
        return new Waiting(loan, since);
    }

    @Test
    @DisplayName("times each decision from when its loan reached Credit, and counts the queue as it stands")
    void timesEachDecision() {
        LocalDateTime ssb42 = LocalDateTime.of(2026, 9, 3, 6, 5, 12);
        LocalDateTime resubmitted42 = LocalDateTime.of(2026, 9, 3, 8, 3, 10);
        when(queue.endedBetween(any(), any())).thenReturn(List.of(
                new Visit(42L, ssb42, LocalDateTime.of(2026, 9, 3, 7, 12, 45), "RETURNED"),
                new Visit(42L, resubmitted42, LocalDateTime.of(2026, 9, 3, 9, 40, 2), "APPROVED"),
                new Visit(43L, LocalDateTime.of(2026, 9, 10, 8, 0), LocalDateTime.of(2026, 9, 11, 14, 0), "REJECTED"),
                new Visit(44L, LocalDateTime.of(2026, 9, 12, 8, 0), LocalDateTime.of(2026, 9, 12, 18, 0), "APPROVED"),
                new Visit(45L, null, LocalDateTime.of(2026, 9, 14, 8, 0), "REJECTED")));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Waiting> waiting = List.of(waiting(50L, now.minusHours(30)), waiting(51L, now.minusHours(50)),
                waiting(52L, now.minusHours(2)));
        when(queue.waiting()).thenReturn(waiting);
        when(workQueueService.currentItems("CREDIT_DECISION", waiting)).thenReturn(Map.of(
                51L, WorkItem.builder().loanId(51L).escalatedAt(now.minusHours(2)).build(),
                52L, WorkItem.builder().loanId(52L).assignedTo("cmanager").build()));

        CreditTurnaroundReportResponse report = service.report(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        // 42 returned after 1.1h and approved 1.6h after the answer; 43 took 30h; 44 took 10h; 45 never reached Credit.
        assertThat(report.decisions()).isEqualTo(4);
        assertThat(report.withinTarget()).isEqualTo(3);
        assertThat(report.adherencePercent()).isEqualByComparingTo("75.0");
        assertThat(report.averageHours()).isEqualByComparingTo("10.7");
        assertThat(report.medianHours()).isEqualByComparingTo("5.8");
        assertThat(report.longestHours()).isEqualByComparingTo("30.0");
        assertThat(report.unmeasured()).isEqualTo(1);
        assertThat(report.byAction()).containsExactly(
                new ActionTurnaround(CreditAction.APPROVED, 2, 2, new BigDecimal("5.8")),
                new ActionTurnaround(CreditAction.REJECTED, 1, 0, new BigDecimal("30.0")),
                new ActionTurnaround(CreditAction.RETURNED, 1, 1, new BigDecimal("1.1")));
        assertThat(report.targetHours()).isEqualTo(24);
        assertThat(report.escalationHours()).isEqualTo(48);
        assertThat(report.awaiting()).isEqualTo(3);
        assertThat(report.overdue()).isEqualTo(2);
        assertThat(report.escalated()).isEqualTo(1);
    }

    @Test
    @DisplayName("the period is the market's days: Harare midnight to midnight, in UTC")
    void periodIsMarketDays() {
        when(queue.endedBetween(any(), any())).thenReturn(List.of());
        when(queue.waiting()).thenReturn(List.of());

        service.report(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(queue).endedBetween(from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 31, 22, 0));
        assertThat(to.getValue()).isBefore(LocalDateTime.of(2026, 9, 30, 22, 0))
                .isAfter(LocalDateTime.of(2026, 9, 30, 21, 59));
    }

    @Test
    @DisplayName("an empty period has no averages rather than zeros, and defaults to this month so far")
    void emptyPeriod() {
        when(queue.endedBetween(any(), any())).thenReturn(List.of());
        when(queue.waiting()).thenReturn(List.of(waiting(50L, LocalDateTime.now(ZoneOffset.UTC).minusHours(1))));

        CreditTurnaroundReportResponse report = service.report(null, null);

        assertThat(report.fromDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(report.toDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(report.decisions()).isZero();
        assertThat(report.adherencePercent()).isNull();
        assertThat(report.averageHours()).isNull();
        assertThat(report.medianHours()).isNull();
        assertThat(report.longestHours()).isNull();
        assertThat(report.byAction()).isEmpty();
        assertThat(report.awaiting()).isEqualTo(1);
        assertThat(report.overdue()).isZero();
    }

    @Test
    @DisplayName("an inverted period is refused")
    void invertedPeriod() {
        assertThatThrownBy(() -> service.report(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("fromDate must not be after toDate");
        verifyNoInteractions(queue);
    }
}
