package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the lodgement job, a credit approval and the booking job ask before moving a loan on (FR-SSB-014). */
class CheckpointGateTest {

    private WorkflowStageRepository stages;
    private LoanRepository loanRepository;
    private CheckpointDecisionRepository decisions;
    private CheckpointGate gate;

    @BeforeEach
    void setUp() {
        stages = mock(WorkflowStageRepository.class);
        loanRepository = mock(LoanRepository.class);
        decisions = mock(CheckpointDecisionRepository.class);
        gate = new CheckpointGate(stages, loanRepository, decisions);
    }

    private static Loan loan(long id, String principal) {
        Loan loan = WorkflowFixtures.loan(id, "tmoyo");
        loan.setLoanApprovalStatus(LoanApprovalStatus.NEW);
        loan.setPrincipal(new BigDecimal(principal));
        return loan;
    }

    private void checkpoints(HoldPoint point, WorkflowStage... active) {
        when(stages.findByKindAndHoldPointAndActiveTrueOrderByDisplayOrderAscCodeAsc(StageKind.CHECKPOINT, point))
                .thenReturn(List.of(active));
    }

    @Test
    @DisplayName("with no checkpoint at the point, every loan passes and nothing more is read")
    void noCheckpoints() {
        assertThat(gate.withoutHeld(HoldPoint.BEFORE_LODGEMENT, List.of(3L, 1L, 2L))).containsExactly(3L, 1L, 2L);
        assertThat(gate.holding(HoldPoint.BEFORE_LODGEMENT, loan(1, "500"))).isEmpty();
        verify(loanRepository, never()).findAllById(anyCollection());
    }

    @Test
    @DisplayName("a due list loses the loans a checkpoint holds, keeping its order, in one read of loans and decisions")
    void dueListFiltered() {
        WorkflowStage large = WorkflowFixtures.checkpoint("LARGE_LOAN_REVIEW", HoldPoint.BEFORE_LODGEMENT);
        large.setMinimumPrincipal(new BigDecimal("2000.00"));
        checkpoints(HoldPoint.BEFORE_LODGEMENT, large);
        when(loanRepository.findAllById(anyCollection())).thenReturn(List.of(
                loan(1, "2500"), loan(2, "500"), loan(3, "3000"), loan(4, "2000")));
        when(decisions.findByStageCodeInAndLoanIdIn(anyCollection(), anyCollection())).thenReturn(List.of(
                CheckpointDecision.builder().stageCode("LARGE_LOAN_REVIEW").loanId(3L).build()));

        assertThat(gate.withoutHeld(HoldPoint.BEFORE_LODGEMENT, List.of(4L, 3L, 2L, 1L, 9L)))
                .as("4 and 1 held; 3 cleared; 2 too small; 9 gone").containsExactly(3L, 2L, 9L);
    }

    @Test
    @DisplayName("under the lock: the first undecided checkpoint that applies holds the loan")
    void holding() {
        WorkflowStage first = WorkflowFixtures.checkpoint("FIRST", HoldPoint.BEFORE_BOOKING);
        WorkflowStage second = WorkflowFixtures.checkpoint("SECOND", HoldPoint.BEFORE_BOOKING);
        checkpoints(HoldPoint.BEFORE_BOOKING, first, second);
        Loan loan = loan(1, "2500");

        assertThat(gate.holding(HoldPoint.BEFORE_BOOKING, loan)).containsSame(first);
        when(decisions.existsByStageCodeAndLoanId("FIRST", 1L)).thenReturn(true);
        assertThat(gate.holding(HoldPoint.BEFORE_BOOKING, loan)).containsSame(second);
        when(decisions.existsByStageCodeAndLoanId("SECOND", 1L)).thenReturn(true);
        assertThat(gate.holding(HoldPoint.BEFORE_BOOKING, loan)).isEmpty();
    }

    @Test
    @DisplayName("a loan's pending checkpoints are the active ones at its current point, applying and undecided")
    void pending() {
        WorkflowStage lodgement = WorkflowFixtures.checkpoint("AGENT_REVIEW", HoldPoint.BEFORE_LODGEMENT);
        WorkflowStage approval = WorkflowFixtures.checkpoint("SECOND_LOOK", HoldPoint.BEFORE_CREDIT_APPROVAL);
        WorkflowStage off = WorkflowFixtures.checkpoint("RETIRED", HoldPoint.BEFORE_CREDIT_APPROVAL);
        off.setActive(false);
        when(stages.findByKindOrderByDisplayOrderAscCodeAsc(StageKind.CHECKPOINT))
                .thenReturn(List.of(lodgement, approval, off));
        Loan loan = loan(1, "2500");
        loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        loan.setInternalApprovalStatus(InternalApprovalStatus.PENDING);

        assertThat(gate.pending(loan)).containsExactly(approval);
        when(decisions.findByStageCodeInAndLoanIdIn(anyCollection(), anyCollection())).thenReturn(List.of(
                CheckpointDecision.builder().stageCode("SECOND_LOOK").loanId(1L).build()));
        assertThat(gate.pending(loan)).isEmpty();
    }
}
