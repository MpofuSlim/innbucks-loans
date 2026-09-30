package zw.co.innbucks.loans.core.workflow;

import zw.co.innbucks.loans.core.loan.LoanRepository;

import static org.mockito.Mockito.mock;

/** Checkpoint gates for tests of what they guard. */
public final class CheckpointGates {

    private CheckpointGates() {
    }

    /** A gate with no checkpoints configured: it holds nothing, as on a fresh install. */
    public static CheckpointGate none() {
        return new CheckpointGate(mock(WorkflowStageRepository.class), mock(LoanRepository.class),
                mock(CheckpointDecisionRepository.class));
    }
}
