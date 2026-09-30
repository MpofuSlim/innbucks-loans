package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.core.workflow.StageQueue.Waiting;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Items waiting past their stage's escalation point are escalated once per wait, and the right people told. */
class WorkflowEscalationServiceTest {

    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

    private WorkflowStage credit;
    private WorkflowStage moreInformation;
    private StageQueue creditQueue;
    private StageQueue moreInformationQueue;
    private WorkQueueService workQueueService;
    private WorkItemRepository itemRepository;
    private WorkItemEventRepository eventRepository;
    private LoanRepository loanRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private AuditService auditService;
    private WorkflowEscalationService service;

    @BeforeEach
    void setUp() {
        credit = WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL);
        moreInformation = WorkflowFixtures.moreInformation();
        WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
        when(stages.findAllByOrderByDisplayOrderAscCodeAsc()).thenReturn(List.of(credit, moreInformation));
        creditQueue = mock(StageQueue.class);
        moreInformationQueue = mock(StageQueue.class);
        when(moreInformationQueue.waiting()).thenReturn(List.of());
        StageQueues queues = mock(StageQueues.class);
        when(queues.of(SystemStage.CREDIT_DECISION)).thenReturn(creditQueue);
        when(queues.of(WorkflowFixtures.stageCoded("CREDIT_DECISION"))).thenReturn(creditQueue);
        when(queues.of(SystemStage.MORE_INFORMATION)).thenReturn(moreInformationQueue);
        when(queues.of(WorkflowFixtures.stageCoded("MORE_INFORMATION"))).thenReturn(moreInformationQueue);
        itemRepository = mock(WorkItemRepository.class);
        when(itemRepository.save(any())).thenAnswer(i -> {
            WorkItem item = i.getArgument(0);
            if (item.getId() == null) {
                item.setId(9L);
            }
            return item;
        });
        eventRepository = mock(WorkItemEventRepository.class);
        workQueueService = spy(new WorkQueueService(mock(WorkflowStageService.class), stages, queues, itemRepository,
                eventRepository, mock(LoanRepository.class), mock(UserRepository.class),
                mock(AuthService.class)));
        loanRepository = mock(LoanRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        service = new WorkflowEscalationService(stages, queues, workQueueService, itemRepository, loanRepository,
                userRepository, notificationService, auditService, mock(PlatformTransactionManager.class));
    }

    private Loan waitingLoan(long id, StageQueue queue, LocalDateTime entered) {
        Loan loan = WorkflowFixtures.loan(id, "tmoyo");
        when(loanRepository.findByIdForUpdate(id)).thenReturn(Optional.of(loan));
        when(queue.enteredAt(loan)).thenReturn(Optional.of(entered));
        return loan;
    }

    private static User withEmail(String username, String email, UserGroup group) {
        User user = WorkflowFixtures.user(username, group);
        user.setEmail(email);
        return user;
    }

    @Test
    @DisplayName("an item past the escalation point is stamped, journaled, audited, and emailed once to each recipient")
    void escalatesOnce() {
        LocalDateTime entered = now.minusHours(50);
        Loan late = waitingLoan(42L, creditQueue, entered);
        Loan early = waitingLoan(43L, creditQueue, now.minusHours(30));
        when(creditQueue.waiting()).thenReturn(List.of(new Waiting(late, entered), new Waiting(early, now.minusHours(30))));
        WorkItem assigned = WorkItem.builder().id(9L).stageCode("CREDIT_DECISION").loanId(42L).enteredAt(entered)
                .assignedTo("cmanager").assignedAt(entered).createdAt(entered).build();
        when(itemRepository.findByStageCodeAndLoanIdIn(eq("CREDIT_DECISION"), anyCollection()))
                .thenReturn(List.of(assigned));
        when(itemRepository.findByStageCodeAndLoanIdAndEnteredAt("CREDIT_DECISION", 42L, entered))
                .thenReturn(Optional.of(assigned));
        credit.getEscalationRoles().add(UserGroup.CREDIT_MANAGER);
        when(userRepository.findByGroupsContaining(UserGroup.SUPER_ADMIN)).thenReturn(List.of(
                withEmail("admin", "ops@innbucks.co.zw", UserGroup.SUPER_ADMIN),
                withEmail("admin2", " ", UserGroup.SUPER_ADMIN)));
        when(userRepository.findByGroupsContaining(UserGroup.CREDIT_MANAGER)).thenReturn(List.of(
                withEmail("cmanager", "Chipo@InnBucks.co.zw", UserGroup.CREDIT_MANAGER)));
        when(userRepository.findByUsernameIn(anyCollection())).thenReturn(List.of(
                withEmail("cmanager", "chipo@innbucks.co.zw", UserGroup.CREDIT_MANAGER)));

        assertThat(service.escalateOverdue()).isEqualTo(1);

        assertThat(assigned.getEscalatedAt()).isNotNull();
        ArgumentCaptor<WorkItemEvent> event = ArgumentCaptor.forClass(WorkItemEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo(WorkItemAction.ESCALATED);
        assertThat(event.getValue().getPerformedBy()).isEqualTo("workflow-escalation-job");
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        AuditLog row = audit.getValue().build();
        assertThat(row.getEventType()).isEqualTo("WORK_ITEM_ESCALATED");
        assertThat(row.getEntityId()).isEqualTo("42");
        assertThat(row.getDetail()).startsWith("stage=CREDIT_DECISION waitingHours=50 ").endsWith("assignedTo=cmanager");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendEmail(eq("ops@innbucks.co.zw"), eq("Overdue: Credit decision, loan 000000042"),
                body.capture());
        // The assignee is also a credit manager: told once.
        verify(notificationService).sendEmail(eq("chipo@innbucks.co.zw"), anyString(), anyString());
        verifyNoMoreInteractions(notificationService);
        assertThat(body.getValue()).contains("Credit decision: loan 000000042, waiting 50 hours (escalation point 48"
                + " hours, target 24 hours), with cmanager").doesNotContain("Rudo");
    }

    @Test
    @DisplayName("an item already escalated for this wait is skipped without locking its loan")
    void alreadyEscalatedSkipped() {
        LocalDateTime entered = now.minusHours(50);
        Loan late = waitingLoan(42L, creditQueue, entered);
        when(creditQueue.waiting()).thenReturn(List.of(new Waiting(late, entered)));
        when(itemRepository.findByStageCodeAndLoanIdIn(eq("CREDIT_DECISION"), anyCollection())).thenReturn(List.of(
                WorkItem.builder().loanId(42L).enteredAt(entered).escalatedAt(now.minusHours(1)).build()));

        assertThat(service.escalateOverdue()).isZero();
        verify(loanRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(notificationService, auditService, eventRepository);
    }

    @Test
    @DisplayName("re-checked under the lock: a loan that left the stage or began a new wait is not escalated")
    void recheckedUnderTheLock() {
        LocalDateTime entered = now.minusHours(50);
        Loan decided = waitingLoan(42L, creditQueue, entered);
        when(creditQueue.enteredAt(decided)).thenReturn(Optional.empty());
        Loan resubmitted = waitingLoan(43L, creditQueue, entered);
        when(creditQueue.enteredAt(resubmitted)).thenReturn(Optional.of(now.minusMinutes(5)));
        when(loanRepository.findByIdForUpdate(44L)).thenReturn(Optional.empty());
        Loan gone = WorkflowFixtures.loan(44L, "tmoyo");
        when(creditQueue.waiting()).thenReturn(List.of(new Waiting(decided, entered), new Waiting(resubmitted, entered),
                new Waiting(gone, entered)));
        when(itemRepository.findByStageCodeAndLoanIdIn(any(), anyCollection())).thenReturn(List.of());

        assertThat(service.escalateOverdue()).isZero();
        verify(itemRepository, never()).save(any());
        verifyNoInteractions(notificationService, auditService);
    }

    @Test
    @DisplayName("one item that fails leaves the others escalated, in one email per recipient")
    void oneFailureDoesNotStopTheRun() {
        LocalDateTime entered = now.minusHours(60);
        Loan first = waitingLoan(41L, creditQueue, entered);
        Loan third = waitingLoan(43L, creditQueue, entered);
        Loan broken = WorkflowFixtures.loan(42L, "tmoyo");
        when(loanRepository.findByIdForUpdate(42L)).thenThrow(new IllegalStateException("lock timeout"));
        when(creditQueue.waiting()).thenReturn(List.of(new Waiting(first, entered), new Waiting(broken, entered),
                new Waiting(third, entered)));
        when(itemRepository.findByStageCodeAndLoanIdIn(any(), anyCollection())).thenReturn(List.of());
        when(itemRepository.findByStageCodeAndLoanIdAndEnteredAt(any(), any(), any())).thenReturn(Optional.empty());
        when(userRepository.findByGroupsContaining(UserGroup.SUPER_ADMIN))
                .thenReturn(List.of(withEmail("admin", "ops@innbucks.co.zw", UserGroup.SUPER_ADMIN)));

        assertThat(service.escalateOverdue()).isEqualTo(2);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendEmail(eq("ops@innbucks.co.zw"), eq("2 workflow items overdue"), body.capture());
        assertThat(body.getValue()).contains("loan 000000041").contains("loan 000000043").doesNotContain("000000042");
        verify(userRepository, times(1)).findByGroupsContaining(UserGroup.SUPER_ADMIN);
    }

    @Test
    @DisplayName("a return waiting on its originator tells the originator; a stage with no escalation point is skipped")
    void originatorToldAndUnescalatedStagesSkipped() {
        LocalDateTime entered = now.minusHours(100);
        Loan returned = waitingLoan(42L, moreInformationQueue, entered);
        returned.setCreatedByUser(withEmail("tmoyo", "tendai@harare-motors.co.zw", UserGroup.AGENTS));
        when(moreInformationQueue.waiting()).thenReturn(List.of(new Waiting(returned, entered)));
        when(itemRepository.findByStageCodeAndLoanIdIn(any(), anyCollection())).thenReturn(List.of());
        when(itemRepository.findByStageCodeAndLoanIdAndEnteredAt(any(), any(), any())).thenReturn(Optional.empty());
        moreInformation.getEscalationRoles().clear();
        credit.setEscalationHours(null);

        assertThat(service.escalateOverdue()).isEqualTo(1);

        verify(notificationService).sendEmail(eq("tendai@harare-motors.co.zw"), eq("Overdue: More information, loan"
                + " 000000042"), anyString());
        verify(creditQueue, never()).waiting();
    }

    @Test
    @DisplayName("with nobody to tell, or an email that cannot be queued, the escalation still stands")
    void escalationStandsWithoutEmail() {
        LocalDateTime entered = now.minusHours(50);
        Loan late = waitingLoan(42L, creditQueue, entered);
        when(creditQueue.waiting()).thenReturn(List.of(new Waiting(late, entered)));
        when(itemRepository.findByStageCodeAndLoanIdIn(any(), anyCollection())).thenReturn(List.of());
        when(itemRepository.findByStageCodeAndLoanIdAndEnteredAt(any(), any(), any())).thenReturn(Optional.empty());
        when(userRepository.findByGroupsContaining(UserGroup.SUPER_ADMIN)).thenReturn(List.of());

        assertThat(service.escalateOverdue()).isEqualTo(1);
        verifyNoInteractions(notificationService);

        Loan again = waitingLoan(43L, creditQueue, entered);
        when(creditQueue.waiting()).thenReturn(List.of(new Waiting(again, entered)));
        when(userRepository.findByGroupsContaining(UserGroup.SUPER_ADMIN))
                .thenReturn(List.of(withEmail("admin", "ops@innbucks.co.zw", UserGroup.SUPER_ADMIN)));
        doThrow(new IllegalStateException("queue full")).when(notificationService).sendEmail(any(), any(), any());
        assertThat(service.escalateOverdue()).isEqualTo(1);
    }

    @Test
    @DisplayName("current items are read through the work queues, one query per stage")
    void readsCurrentItemsPerStage() {
        when(creditQueue.waiting()).thenReturn(List.of());

        service.escalateOverdue();

        verify(workQueueService).currentItems(eq("CREDIT_DECISION"), anyList());
        verify(workQueueService).currentItems(eq("MORE_INFORMATION"), anyList());
    }
}
