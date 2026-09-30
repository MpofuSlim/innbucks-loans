package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.core.workflow.StageQueue.Visit;
import zw.co.innbucks.loans.core.workflow.StageQueue.Waiting;
import zw.co.innbucks.loans.core.workflow.WorkflowPipelineReportResponse.Breakdown;
import zw.co.innbucks.loans.core.workflow.WorkflowPipelineReportResponse.StagePipeline;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The workflow pipeline and adherence by stage, channel and originating agent (FR-SSB-014, BRD 7.4). */
class WorkflowReportServiceTest {

    /** 10:00 on 30 September in Harare. */
    private static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");

    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    private StageQueue creditQueue;
    private StageQueue moreInformationQueue;
    private WorkItemRepository itemRepository;
    private LoanRepository loanRepository;
    private WorkflowReportService service;

    @BeforeEach
    void setUp() {
        WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
        when(stages.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(
                WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL), WorkflowFixtures.moreInformation()));
        creditQueue = mock(StageQueue.class);
        moreInformationQueue = mock(StageQueue.class);
        when(moreInformationQueue.waiting()).thenReturn(List.of());
        when(moreInformationQueue.endedBetween(any(), any())).thenReturn(List.of());
        StageQueues queues = mock(StageQueues.class);
        when(queues.of(SystemStage.CREDIT_DECISION)).thenReturn(creditQueue);
        when(queues.of(SystemStage.MORE_INFORMATION)).thenReturn(moreInformationQueue);
        itemRepository = mock(WorkItemRepository.class);
        when(itemRepository.findByStageCodeAndLoanIdIn(any(), anyCollection())).thenReturn(List.of());
        loanRepository = mock(LoanRepository.class);
        WorkQueueService workQueueService = new WorkQueueService(mock(WorkflowStageService.class), stages, queues,
                itemRepository, mock(WorkItemEventRepository.class), loanRepository, mock(UserRepository.class),
                mock(AuthService.class));
        service = new WorkflowReportService(stages, queues, workQueueService, loanRepository,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static Loan loan(long id, String originator, Channel channel) {
        Loan loan = WorkflowFixtures.loan(id, originator);
        loan.setChannel(channel);
        User user = WorkflowFixtures.user(originator, UserGroup.AGENTS);
        user.setFirstName("Tendai");
        user.setLastName("Moyo");
        loan.setCreatedByUser("tmoyo".equals(originator) ? user : null);
        return loan;
    }

    @Test
    @DisplayName("each stage's queue now, and the waits that ended in the period, by channel and originator")
    void pipeline() {
        Channel superApp = new Channel();
        superApp.setChannelId("superapp");
        superApp.setName("InnBucks SuperApp");
        Loan portal1 = loan(41L, "tmoyo", null);
        Loan portal2 = loan(42L, "tmoyo", null);
        Loan app = loan(44L, "superapp", superApp);
        when(creditQueue.waiting()).thenReturn(List.of(new Waiting(app, now.minusHours(30)),
                new Waiting(portal2, now.minusHours(2))));
        when(itemRepository.findByStageCodeAndLoanIdIn(any(), anyCollection())).thenReturn(List.of(
                WorkItem.builder().loanId(44L).enteredAt(now.minusHours(30)).escalatedAt(now).build(),
                WorkItem.builder().loanId(42L).enteredAt(now.minusHours(2)).assignedTo("cmanager").build()));
        LocalDateTime day = LocalDateTime.of(2026, 9, 10, 8, 0);
        when(creditQueue.endedBetween(any(), any())).thenReturn(List.of(
                new Visit(41L, day, day.plusHours(2), "APPROVED"),
                new Visit(41L, day.plusDays(1), day.plusDays(1).plusHours(30), "REJECTED"),
                new Visit(44L, day, day.plusHours(10), "APPROVED"),
                new Visit(45L, null, day, "REJECTED")));
        when(loanRepository.findAllById(any())).thenReturn(List.of(portal1, app));

        WorkflowPipelineReportResponse report = service.pipeline(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(report.stages()).extracting(StagePipeline::stage).containsExactly("CREDIT_DECISION",
                "MORE_INFORMATION");
        StagePipeline credit = report.stages().getFirst();
        assertThat(credit.waiting()).isEqualTo(2);
        assertThat(credit.overdue()).isEqualTo(1);
        assertThat(credit.escalated()).isEqualTo(1);
        assertThat(credit.unassigned()).isEqualTo(1);
        assertThat(credit.completed()).isEqualTo(3);
        assertThat(credit.withinTarget()).isEqualTo(2);
        assertThat(credit.adherencePercent()).isEqualByComparingTo("66.7");
        assertThat(credit.averageHours()).isEqualByComparingTo("14.0");
        assertThat(credit.medianHours()).isEqualByComparingTo("10.0");
        assertThat(credit.longestHours()).isEqualByComparingTo("30.0");
        assertThat(credit.unmeasured()).isEqualTo(1);
        assertThat(credit.byChannel()).containsExactly(
                new Breakdown("PORTAL", "Portal", 1, 0, 2, 1, new BigDecimal("50.0"), new BigDecimal("16.0")),
                new Breakdown("superapp", "InnBucks SuperApp", 1, 1, 1, 1, new BigDecimal("100.0"),
                        new BigDecimal("10.0")));
        assertThat(credit.byOriginator()).containsExactly(
                new Breakdown("superapp", null, 1, 1, 1, 1, new BigDecimal("100.0"), new BigDecimal("10.0")),
                new Breakdown("tmoyo", "Tendai Moyo", 1, 0, 2, 1, new BigDecimal("50.0"), new BigDecimal("16.0")));
        StagePipeline moreInformation = report.stages().get(1);
        assertThat(moreInformation.unassigned()).isNull();
        assertThat(moreInformation.completed()).isZero();
        assertThat(moreInformation.adherencePercent()).isNull();
        assertThat(moreInformation.byChannel()).isEmpty();
    }

    @Test
    @DisplayName("the period is the market's days, this month so far by default; an inverted one is refused")
    void period() {
        when(creditQueue.waiting()).thenReturn(List.of());
        when(creditQueue.endedBetween(any(), any())).thenReturn(List.of());

        WorkflowPipelineReportResponse report = service.pipeline(null, null);

        assertThat(report.fromDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(report.toDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        verify(creditQueue).endedBetween(eq(LocalDateTime.of(2026, 8, 31, 22, 0)), any());
        verify(loanRepository, never()).findAllById(any());
        assertThatThrownBy(() -> service.pipeline(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("fromDate must not be after toDate");
    }
}
