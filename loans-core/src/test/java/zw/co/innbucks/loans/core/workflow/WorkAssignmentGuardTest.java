package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.loan.Loan;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** At an EXCLUSIVE stage, an assigned item is its assignee's to act on (FR-SSB-014). */
class WorkAssignmentGuardTest {

    private static final LocalDateTime ENTERED = LocalDateTime.of(2026, 9, 30, 8, 3, 10);

    private WorkflowStageRepository stages;
    private WorkItemRepository items;
    private StageQueue queue;
    private WorkAssignmentGuard guard;
    private final Loan loan = WorkflowFixtures.loan(42L, "tmoyo");

    @BeforeEach
    void setUp() {
        stages = mock(WorkflowStageRepository.class);
        items = mock(WorkItemRepository.class);
        queue = mock(StageQueue.class);
        StageQueues queues = mock(StageQueues.class);
        when(queues.of(SystemStage.CREDIT_DECISION)).thenReturn(queue);
        when(queues.of(WorkflowFixtures.stageCoded("CREDIT_DECISION"))).thenReturn(queue);
        when(queue.enteredAt(loan)).thenReturn(Optional.of(ENTERED));
        guard = new WorkAssignmentGuard(stages, queues, items);
    }

    private void assignedTo(String username) {
        when(items.findByStageCodeAndLoanIdAndEnteredAt("CREDIT_DECISION", 42L, ENTERED)).thenReturn(Optional.of(
                WorkItem.builder().stageCode("CREDIT_DECISION").loanId(42L).enteredAt(ENTERED).assignedTo(username)
                        .assignedAt(ENTERED).build()));
    }

    @Test
    @DisplayName("at an EXCLUSIVE stage, someone else's item is refused (409) naming who has it")
    void someoneElsesItemIsRefused() {
        when(stages.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.EXCLUSIVE)));
        assignedTo("rnyathi");

        assertThatThrownBy(() -> guard.requireMayAct(SystemStage.CREDIT_DECISION, loan, "cmanager"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Loan 000000042's Credit decision is assigned to rnyathi; only they can act on it until it"
                        + " is released or reassigned");
        assertThatCode(() -> guard.requireMayAct(SystemStage.CREDIT_DECISION, loan, "RNYATHI"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unassigned item, or one from an earlier wait, is anyone's to act on")
    void unassignedIsAnyones() {
        when(stages.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.EXCLUSIVE)));
        when(items.findByStageCodeAndLoanIdAndEnteredAt(any(), any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> guard.requireMayAct(SystemStage.CREDIT_DECISION, loan, "cmanager"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an OPTIONAL stage routes work without holding anyone to it, and a loan not waiting there is not checked")
    void optionalRoutesOnly() {
        when(stages.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL)));
        assignedTo("rnyathi");

        assertThatCode(() -> guard.requireMayAct(SystemStage.CREDIT_DECISION, loan, "cmanager"))
                .doesNotThrowAnyException();
        verifyNoInteractions(items);

        when(stages.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.EXCLUSIVE)));
        when(queue.enteredAt(loan)).thenReturn(Optional.empty());
        assertThatCode(() -> guard.requireMayAct(SystemStage.CREDIT_DECISION, loan, "cmanager"))
                .doesNotThrowAnyException();
    }
}
