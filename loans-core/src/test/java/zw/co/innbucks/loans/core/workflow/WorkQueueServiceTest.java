package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.core.workflow.StageQueue.Waiting;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** The work queues: what waits where, and who has it (FR-SSB-014). */
class WorkQueueServiceTest {

    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    private final LocalDateTime entered = now.minusHours(2);

    private final User cmanager = named(WorkflowFixtures.user("cmanager", UserGroup.CREDIT_MANAGER), "Chipo", "Manyika");
    private final User rnyathi = named(WorkflowFixtures.user("rnyathi", UserGroup.CREDIT_MANAGER), "Rufaro", "Nyathi");
    private final User fmoyo = WorkflowFixtures.user("fmoyo", UserGroup.FINANCE);
    private final User admin = WorkflowFixtures.user("admin", UserGroup.SUPER_ADMIN);
    private final User tmoyo = named(WorkflowFixtures.user("tmoyo", UserGroup.AGENTS), "Tendai", "Moyo");

    private WorkflowStage credit;
    private WorkflowStageRepository stageRepository;
    private StageQueue creditQueue;
    private StageQueue moreInformationQueue;
    private WorkItemRepository itemRepository;
    private WorkItemEventRepository eventRepository;
    private LoanRepository loanRepository;
    private UserRepository userRepository;
    private AuthService authService;
    private WorkQueueService service;
    private Loan loan;

    private static User named(User user, String first, String last) {
        user.setFirstName(first);
        user.setLastName(last);
        return user;
    }

    @BeforeEach
    void setUp() {
        credit = WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL);
        WorkflowStage moreInformation = WorkflowFixtures.moreInformation();
        stageRepository = mock(WorkflowStageRepository.class);
        when(stageRepository.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(credit, moreInformation));
        WorkflowStageService stageService = mock(WorkflowStageService.class);
        when(stageService.stage("CREDIT_DECISION")).thenReturn(credit);
        when(stageService.stage("MORE_INFORMATION")).thenReturn(moreInformation);
        when(stageService.stage("BOOKING")).thenThrow(new NotFoundException("No workflow stage BOOKING"));

        creditQueue = mock(StageQueue.class);
        moreInformationQueue = mock(StageQueue.class);
        StageQueues queues = mock(StageQueues.class);
        when(queues.of(SystemStage.CREDIT_DECISION)).thenReturn(creditQueue);
        when(queues.of(SystemStage.MORE_INFORMATION)).thenReturn(moreInformationQueue);
        when(moreInformationQueue.waiting()).thenReturn(List.of());

        loan = WorkflowFixtures.loan(42L, "tmoyo");
        loan.setPrincipal(new BigDecimal("531.91"));
        loanRepository = mock(LoanRepository.class);
        when(loanRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));
        when(creditQueue.enteredAt(loan)).thenReturn(Optional.of(entered));
        when(creditQueue.waiting()).thenReturn(List.of(new Waiting(loan, entered)));

        itemRepository = mock(WorkItemRepository.class);
        when(itemRepository.save(any())).thenAnswer(i -> {
            WorkItem item = i.getArgument(0);
            if (item.getId() == null) {
                item.setId(7L);
            }
            return item;
        });
        eventRepository = mock(WorkItemEventRepository.class);

        userRepository = mock(UserRepository.class);
        for (User user : List.of(cmanager, rnyathi, fmoyo, admin, tmoyo)) {
            when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
        }
        when(userRepository.findByUsernameIn(anyCollection())).thenReturn(List.of(cmanager, rnyathi, tmoyo));
        authService = mock(AuthService.class);
        as(cmanager);

        service = new WorkQueueService(stageService, stageRepository, queues, itemRepository, eventRepository,
                loanRepository, userRepository, authService);
    }

    private void as(User user) {
        when(authService.getLoggedInUser()).thenReturn(user);
        when(authService.getLoggedInUsername()).thenReturn(user.getUsername());
    }

    private WorkItem item(String assignedTo) {
        WorkItem item = WorkItem.builder().id(7L).stageCode("CREDIT_DECISION").loanId(42L).enteredAt(entered)
                .assignedTo(assignedTo).assignedAt(assignedTo == null ? null : entered.plusMinutes(5)).createdAt(entered)
                .build();
        when(itemRepository.findByStageCodeAndLoanIdAndEnteredAt("CREDIT_DECISION", 42L, entered))
                .thenReturn(Optional.of(item));
        when(itemRepository.findByStageCodeAndLoanIdIn(any(), anyCollection())).thenReturn(List.of(item));
        return item;
    }

    private WorkItemEvent recorded() {
        ArgumentCaptor<WorkItemEvent> event = ArgumentCaptor.forClass(WorkItemEvent.class);
        verify(eventRepository).save(event.capture());
        return event.getValue();
    }

    @Nested
    @DisplayName("queues")
    class Queues {

        @Test
        @DisplayName("each stage the caller sees, with what waits, is overdue or escalated, nobody's and theirs")
        void summaries() {
            Loan overdue = WorkflowFixtures.loan(44L, "tmoyo");
            when(creditQueue.waiting()).thenReturn(List.of(new Waiting(overdue, now.minusHours(50)),
                    new Waiting(loan, entered)));
            when(itemRepository.findByStageCodeAndLoanIdIn(any(), anyCollection())).thenReturn(List.of(
                    WorkItem.builder().loanId(44L).enteredAt(now.minusHours(50)).escalatedAt(now.minusHours(2)).build(),
                    WorkItem.builder().loanId(42L).enteredAt(entered).assignedTo("cmanager").assignedAt(now).build()));

            List<WorkQueueSummary> summaries = service.summaries();

            assertThat(summaries).containsExactly(
                    new WorkQueueSummary("CREDIT_DECISION", "Credit decision", AssignmentMode.OPTIONAL, 24, 48, 2, 1, 1,
                            1L, 1L),
                    new WorkQueueSummary("MORE_INFORMATION", "More information", AssignmentMode.NONE, 48, 96, 0, 0, 0,
                            null, null));
        }

        @Test
        @DisplayName("an item from an earlier wait is not this wait's: its assignee and escalation do not carry over")
        void earlierWaitsItemsIgnored() {
            when(itemRepository.findByStageCodeAndLoanIdIn(any(), anyCollection())).thenReturn(List.of(
                    WorkItem.builder().loanId(42L).enteredAt(entered.minusDays(2)).assignedTo("cmanager")
                            .assignedAt(entered.minusDays(2)).escalatedAt(entered.minusHours(1)).build()));

            WorkQueueSummary summary = service.summaries().getFirst();

            assertThat(summary.escalated()).isZero();
            assertThat(summary.unassigned()).isEqualTo(1);
            assertThat(summary.assignedToMe()).isZero();
        }

        @Test
        @DisplayName("a stage the caller does not see is left out")
        void unseenStagesLeftOut() {
            as(fmoyo);

            assertThat(service.summaries()).isEmpty();
            verifyNoInteractions(creditQueue);
        }

        @Test
        @DisplayName("items carry who the applicant and originator are, the service level, and who has it")
        void items() {
            item("rnyathi");

            WorkItemResponse only = service.items("CREDIT_DECISION", null).getFirst();

            assertThat(only.loanId()).isEqualTo(42L);
            assertThat(only.reference()).isEqualTo("000000042");
            assertThat(only.applicantName()).isEqualTo("Rudo Chikwanha");
            assertThat(only.principal()).isEqualByComparingTo("531.91");
            assertThat(only.channelId()).isNull();
            assertThat(only.originator()).isEqualTo("tmoyo");
            assertThat(only.originatorName()).isEqualTo("Tendai Moyo");
            assertThat(only.enteredAt()).isEqualTo(entered);
            assertThat(only.dueAt()).isEqualTo(entered.plusHours(24));
            assertThat(only.escalatesAt()).isEqualTo(entered.plusHours(48));
            assertThat(only.waitingHours()).isEqualByComparingTo("2.0");
            assertThat(only.overdue()).isFalse();
            assertThat(only.assignedTo()).isEqualTo("rnyathi");
            assertThat(only.assignedToName()).isEqualTo("Rufaro Nyathi");
        }

        @Test
        @DisplayName("items filter to the caller's, to nobody's, or to one person's")
        void itemsFiltered() {
            item("cmanager");

            assertThat(service.items("CREDIT_DECISION", "me")).hasSize(1);
            assertThat(service.items("CREDIT_DECISION", "CManager")).hasSize(1);
            assertThat(service.items("CREDIT_DECISION", "none")).isEmpty();
            assertThat(service.items("CREDIT_DECISION", "rnyathi")).isEmpty();
            assertThatThrownBy(() -> service.items("BOOKING", null)).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("my work is what I have at stages whose items are assigned")
        void mine() {
            item("cmanager");
            assertThat(service.mine()).extracting(WorkItemResponse::loanId).containsExactly(42L);

            as(rnyathi);
            assertThat(service.mine()).isEmpty();
            verify(moreInformationQueue, never()).waiting();
        }
    }

    @Nested
    @DisplayName("assignment")
    class Assignment {

        @Test
        @DisplayName("anyone who works the stage takes an unassigned item for themselves")
        void workerTakesAnItem() {
            when(itemRepository.findByStageCodeAndLoanIdAndEnteredAt(any(), any(), any())).thenReturn(Optional.empty());

            WorkItemResponse taken = service.assign("CREDIT_DECISION", 42L, null);

            assertThat(taken.assignedTo()).isEqualTo("cmanager");
            assertThat(taken.assignedToName()).isEqualTo("Chipo Manyika");
            ArgumentCaptor<WorkItem> saved = ArgumentCaptor.forClass(WorkItem.class);
            verify(itemRepository).save(saved.capture());
            assertThat(saved.getValue().getStageCode()).isEqualTo("CREDIT_DECISION");
            assertThat(saved.getValue().getEnteredAt()).isEqualTo(entered);
            WorkItemEvent event = recorded();
            assertThat(event.getAction()).isEqualTo(WorkItemAction.ASSIGNED);
            assertThat(event.getFromUser()).isNull();
            assertThat(event.getToUser()).isEqualTo("cmanager");
            assertThat(event.getPerformedBy()).isEqualTo("cmanager");
            assertThat(event.getWorkItemId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("someone who assigns the stage gives an item from one person to another")
        void supervisorReassigns() {
            item("rnyathi");
            as(admin);

            assertThat(service.assign("CREDIT_DECISION", 42L, " cmanager ").assignedTo()).isEqualTo("cmanager");

            WorkItemEvent event = recorded();
            assertThat(event.getAction()).isEqualTo(WorkItemAction.REASSIGNED);
            assertThat(event.getFromUser()).isEqualTo("rnyathi");
            assertThat(event.getToUser()).isEqualTo("cmanager");
            assertThat(event.getPerformedBy()).isEqualTo("admin");
        }

        @Test
        @DisplayName("a worker without ASSIGN gives nothing to others and takes nobody's item; nothing changes")
        void workerCannotReassign() {
            credit.getRoles().remove(new StageRole(UserGroup.CREDIT_MANAGER, Entitlement.ASSIGN));
            item("rnyathi");

            assertThatThrownBy(() -> service.assign("CREDIT_DECISION", 42L, "rnyathi"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("You may take Credit decision items for yourself, but not give them to others");
            assertThatThrownBy(() -> service.assign("CREDIT_DECISION", 42L, null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Loan 000000042's Credit decision is assigned to rnyathi; someone who assigns Credit"
                            + " decision can reassign it");
            verify(itemRepository, never()).save(any());
            verifyNoInteractions(eventRepository);
        }

        @Test
        @DisplayName("someone who does not work the stage cannot take its items")
        void nonWorkerCannotTake() {
            as(fmoyo);

            assertThatThrownBy(() -> service.assign("CREDIT_DECISION", 42L, null))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("You do not work Credit decision");
            verifyNoInteractions(loanRepository);
        }

        @Test
        @DisplayName("an item goes only to someone who works the stage and is not the loan's originator or party")
        void assigneeMustBeEligible() {
            when(itemRepository.findByStageCodeAndLoanIdAndEnteredAt(any(), any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assign("CREDIT_DECISION", 42L, "fmoyo"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("fmoyo does not work Credit decision");
            assertThatThrownBy(() -> service.assign("CREDIT_DECISION", 42L, "nobody"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No user nobody");
            loan.setCreatedBy("rnyathi");
            assertThatThrownBy(() -> service.assign("CREDIT_DECISION", 42L, "rnyathi"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("rnyathi originated loan 000000042 or is a party to it, so cannot be given its Credit"
                            + " decision");
            loan.setCreatedBy("tmoyo");
            rnyathi.setMobileNumber("+263 77 123 4567");
            assertThatThrownBy(() -> service.assign("CREDIT_DECISION", 42L, "rnyathi"))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(itemRepository, never()).save(any());
        }

        @Test
        @DisplayName("a loan not waiting at the stage, or a stage whose items are not assigned, is refused (409)")
        void notWaitingOrNotAssigned() {
            when(creditQueue.enteredAt(loan)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assign("CREDIT_DECISION", 42L, null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Loan 000000042 is not waiting at Credit decision");
            assertThatThrownBy(() -> service.assign("MORE_INFORMATION", 42L, null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("More information items are not assigned");
            credit.setAssignment(AssignmentMode.NONE);
            assertThatThrownBy(() -> service.assign("CREDIT_DECISION", 42L, null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Credit decision items are not assigned");
            assertThatThrownBy(() -> service.assign("BOOKING", 42L, null)).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("giving an item to whoever has it changes nothing")
        void sameAssigneeIsANoOp() {
            item("cmanager");

            assertThat(service.assign("CREDIT_DECISION", 42L, "CMANAGER").assignedTo()).isEqualTo("cmanager");
            verify(itemRepository, never()).save(any());
            verifyNoInteractions(eventRepository);
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("the assignee releases their own item")
        void ownReleased() {
            WorkItem item = item("cmanager");

            assertThat(service.release("CREDIT_DECISION", 42L).assignedTo()).isNull();

            assertThat(item.getAssignedTo()).isNull();
            assertThat(item.getAssignedAt()).isNull();
            WorkItemEvent event = recorded();
            assertThat(event.getAction()).isEqualTo(WorkItemAction.RELEASED);
            assertThat(event.getFromUser()).isEqualTo("cmanager");
            assertThat(event.getToUser()).isNull();
        }

        @Test
        @DisplayName("someone else's item is released only by someone who assigns the stage")
        void othersReleasedBySupervisors() {
            credit.getRoles().remove(new StageRole(UserGroup.CREDIT_MANAGER, Entitlement.ASSIGN));
            item("rnyathi");

            assertThatThrownBy(() -> service.release("CREDIT_DECISION", 42L))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("Loan 000000042's Credit decision is rnyathi's; only they or someone who assigns"
                            + " Credit decision can release it");

            as(admin);
            service.release("CREDIT_DECISION", 42L);
            assertThat(recorded().getPerformedBy()).isEqualTo("admin");
        }

        @Test
        @DisplayName("releasing an item nobody has changes nothing")
        void nothingToRelease() {
            when(itemRepository.findByStageCodeAndLoanIdAndEnteredAt(any(), any(), any())).thenReturn(Optional.empty());

            assertThat(service.release("CREDIT_DECISION", 42L).assignedTo()).isNull();
            verify(itemRepository, never()).save(any());
            verifyNoInteractions(eventRepository);
        }
    }

    @Test
    @DisplayName("a loan's work history names each event's stage and wait, oldest first")
    void history() {
        when(loanRepository.existsById(42L)).thenReturn(true);
        when(itemRepository.findByLoanIdOrderByIdAsc(42L)).thenReturn(List.of(
                WorkItem.builder().id(7L).stageCode("CREDIT_DECISION").loanId(42L).enteredAt(entered).build()));
        when(eventRepository.findByWorkItemIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                WorkItemEvent.builder().id(1L).workItemId(7L).action(WorkItemAction.ASSIGNED).toUser("rnyathi")
                        .performedBy("admin").performedAt(now).build(),
                WorkItemEvent.builder().id(2L).workItemId(7L).action(WorkItemAction.REASSIGNED).fromUser("rnyathi")
                        .toUser("cmanager").performedBy("admin").performedAt(now).build()));

        assertThat(service.history(42L)).containsExactly(
                new WorkItemEventResponse(1L, "CREDIT_DECISION", 42L, entered, WorkItemAction.ASSIGNED, null,
                        "rnyathi", "admin", now),
                new WorkItemEventResponse(2L, "CREDIT_DECISION", 42L, entered, WorkItemAction.REASSIGNED, "rnyathi",
                        "cmanager", "admin", now));

        when(loanRepository.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.history(99L)).isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 99 not found");
        when(itemRepository.findByLoanIdOrderByIdAsc(43L)).thenReturn(List.of());
        when(loanRepository.existsById(43L)).thenReturn(true);
        assertThat(service.history(43L)).isEmpty();
    }

    @Test
    @DisplayName("each waiting loan's current item is found by stage, loan and when its wait began")
    void currentItems() {
        LocalDateTime other = entered.minusDays(1);
        when(itemRepository.findByStageCodeAndLoanIdIn(any(), anyCollection())).thenReturn(List.of(
                WorkItem.builder().loanId(42L).enteredAt(other).build(),
                WorkItem.builder().loanId(42L).enteredAt(entered).assignedTo("cmanager").build()));

        Map<Long, WorkItem> items = service.currentItems("CREDIT_DECISION", List.of(new Waiting(loan, entered)));

        assertThat(items).hasSize(1);
        assertThat(items.get(42L).getAssignedTo()).isEqualTo("cmanager");
        assertThat(service.currentItems("CREDIT_DECISION", List.of())).isEmpty();
    }
}
