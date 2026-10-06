package zw.co.innbucks.loans.core.saga;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.ledger.DisbursementLedger;
import zw.co.innbucks.loans.core.ledger.LedgerEntryRepository;
import zw.co.innbucks.loans.core.ledger.LedgerService;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanPublicReferenceService;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.LoanStatusSnapshot;
import zw.co.innbucks.loans.core.workflow.WorkAssignmentGuard;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The reconciler used to read a lodged-but-unanswered loan (PROCESSING) as SSB-verified, look only
 * at loans created in the last 30 days, load all of them in full every minute, and audit the same
 * illegal transition every minute for as long as a loan sat in one. These pin the fixes.
 */
class LoanSagaReconcileTest {

    private LoanRepository loanRepository;
    private LoanSagaRepository sagaRepository;
    private AuditService auditService;
    private LoanSagaTransitionService transitions;
    private Loan loan;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        sagaRepository = mock(LoanSagaRepository.class);
        auditService = mock(AuditService.class);
        transitions = new LoanSagaTransitionService(loanRepository, sagaRepository,
                new DisbursementLedger(mock(LedgerService.class), mock(LedgerEntryRepository.class)),
                auditService, mock(LoanPublicReferenceService.class),
                new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class),
                        mock(WorkAssignmentGuard.class), new MarketTimeZone("ZW")));
        loan = Loan.builder().loanApprovalStatus(LoanApprovalStatus.PROCESSING).build();
        loan.setId(42L);
        when(loanRepository.findById(42L)).thenReturn(Optional.of(loan));
    }

    private LoanSaga saga(LoanSagaState state) {
        LoanSaga saga = LoanSaga.builder().id(1L).loanId(42L).currentState(state)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).lastTransitionAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        when(sagaRepository.findByLoanId(42L)).thenReturn(Optional.of(saga));
        return saga;
    }

    private static LoanStatusSnapshot snapshot(long id, LoanApprovalStatus approval, InternalApprovalStatus internal) {
        return new LoanStatusSnapshot(id, approval, internal, null, null);
    }

    // ── Transitions ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a lodged loan Ndasenda has not answered stays in SSB verification")
    void unansweredLodgementIsNotVerified() {
        LoanSaga saga = saga(LoanSagaState.SSB_VERIFICATION_PENDING);

        transitions.reconcileLoan(42L);

        assertThat(saga.getCurrentState()).isEqualTo(LoanSagaState.SSB_VERIFICATION_PENDING);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("a saga moved to credit assessment on an unanswered lodgement still takes Ndasenda's refusal")
    void legacySagaTakesNdasendasRefusal() {
        LoanSaga saga = saga(LoanSagaState.CREDIT_ASSESSMENT_PENDING);
        loan.setLoanApprovalStatus(LoanApprovalStatus.REJECTED);

        transitions.reconcileLoan(42L);

        assertThat(saga.getCurrentState()).isEqualTo(LoanSagaState.SSB_REJECTED);
        verify(auditService).recordTransition(eq("LOAN_SAGA"), eq("42"), anyString(), anyString(),
                eq("CREDIT_ASSESSMENT_PENDING"), eq("SSB_REJECTED"), any(), anyString());
    }

    @Test
    @DisplayName("an illegal transition is reported once, not on every tick, and cleared by the next real move")
    void anomalyIsReportedOnce() {
        LoanSaga saga = saga(LoanSagaState.DISBURSEMENT_PENDING);
        // Resolves to CREDIT_APPROVED: not a move the state machine allows from DISBURSEMENT_PENDING.
        loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        loan.setInternalApprovalStatus(InternalApprovalStatus.APPROVED);
        loan.setLoanAccountStatus(LoanAccountStatus.PENDING);

        transitions.reconcileLoan(42L);
        transitions.reconcileLoan(42L);
        transitions.reconcileLoan(42L);

        verify(auditService, times(1)).recordTransition(anyString(), anyString(), anyString(), anyString(),
                eq("DISBURSEMENT_PENDING"), eq("CREDIT_APPROVED"), eq("ILLEGAL TRANSITION — flagged for operator review"),
                anyString());
        assertThat(saga.getCurrentState()).isEqualTo(LoanSagaState.DISBURSEMENT_PENDING);
        assertThat(saga.getFlaggedAnomaly()).isEqualTo("CREDIT_APPROVED");

        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        loan.setDisbursementStatus(LoanDisbursementStatus.FAILED);
        transitions.reconcileLoan(42L);

        assertThat(saga.getCurrentState()).isEqualTo(LoanSagaState.COMPENSATED);
        assertThat(saga.getFlaggedAnomaly()).isNull();
    }

    // ── The reconciler loop ──────────────────────────────────────────────────

    @Test
    @DisplayName("only a loan that moved is loaded and reconciled; a terminal or already-reported one is not")
    void onlyMovedLoansAreReconciled() {
        LoanSagaTransitionService transitionService = mock(LoanSagaTransitionService.class);
        LoanSagaOrchestrator orchestrator = new LoanSagaOrchestrator(sagaRepository, transitionService);
        LoanSaga unchanged = LoanSaga.builder().loanId(1L).currentState(LoanSagaState.CREDIT_ASSESSMENT_PENDING).build();
        LoanSaga moved = LoanSaga.builder().loanId(2L).currentState(LoanSagaState.CREDIT_ASSESSMENT_PENDING).build();
        LoanSaga terminal = LoanSaga.builder().loanId(3L).currentState(LoanSagaState.CREDIT_REJECTED).build();
        LoanSaga reported = LoanSaga.builder().loanId(4L).currentState(LoanSagaState.DISBURSEMENT_PENDING)
                .flaggedAnomaly("CREDIT_APPROVED").build();
        when(sagaRepository.findByLoanIdIn(any())).thenReturn(List.of(unchanged, moved, terminal, reported));
        when(sagaRepository.findReconcileCandidates(any(), any(), anyLong(), any())).thenReturn(List.of(
                snapshot(1L, LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING),
                snapshot(2L, LoanApprovalStatus.APPROVED, InternalApprovalStatus.REJECTED),
                snapshot(3L, LoanApprovalStatus.APPROVED, InternalApprovalStatus.REJECTED),
                new LoanStatusSnapshot(4L, LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED,
                        LoanAccountStatus.PENDING, null),
                snapshot(5L, LoanApprovalStatus.NEW, null)));

        orchestrator.reconcile();

        verify(transitionService).reconcileLoan(2L);
        verify(transitionService).reconcileLoan(5L); // no saga yet: opened
        verify(transitionService, never()).reconcileLoan(1L);
        verify(transitionService, never()).reconcileLoan(3L);
        verify(transitionService, never()).reconcileLoan(4L);
    }

    @Test
    @DisplayName("open sagas are followed at any age; only a loan with no saga yet is bounded to 30 days")
    void openSagasAreFollowedAtAnyAge() {
        LoanSagaOrchestrator orchestrator = new LoanSagaOrchestrator(sagaRepository, mock(LoanSagaTransitionService.class));

        orchestrator.reconcile();

        LocalDateTime lowerBound = LocalDateTime.now(ZoneOffset.UTC).minusDays(30).minusMinutes(1);
        verify(sagaRepository).findReconcileCandidates(argThat(since -> since.isAfter(lowerBound)),
                eq(LoanSagaState.terminalStates()), eq(0L), any());
        // Status columns only: the orchestrator never loads a whole loan itself.
        verifyNoInteractions(loanRepository);
    }

    @Test
    @DisplayName("one loan's failure never stops the others")
    void oneFailureDoesNotStopTheRun() {
        LoanSagaTransitionService transitionService = mock(LoanSagaTransitionService.class);
        doThrow(new IllegalStateException("boom")).when(transitionService).reconcileLoan(1L);
        when(sagaRepository.findReconcileCandidates(any(), any(), anyLong(), any())).thenReturn(List.of(
                snapshot(1L, LoanApprovalStatus.NEW, null), snapshot(2L, LoanApprovalStatus.NEW, null)));

        new LoanSagaOrchestrator(sagaRepository, transitionService).reconcile();

        verify(transitionService).reconcileLoan(2L);
    }

    @Test
    @DisplayName("candidates are read a chunk at a time, each with its own sagas: every loan reconciled once, a failure"
            + " never stops the rest, and open compensations are driven a chunk at a time too")
    void candidatesAreWalkedInChunks() {
        LoanSagaTransitionService transitionService = mock(LoanSagaTransitionService.class);
        doThrow(new IllegalStateException("boom")).when(transitionService).reconcileLoan(120L);
        List<LoanStatusSnapshot> all = java.util.stream.LongStream.rangeClosed(1, 150)
                .mapToObj(id -> snapshot(id, LoanApprovalStatus.NEW, null)).toList();
        when(sagaRepository.findReconcileCandidates(any(), any(), anyLong(), any())).thenAnswer(call -> {
            long after = call.getArgument(2);
            org.springframework.data.domain.Pageable chunk = call.getArgument(3);
            return all.stream().filter(loan -> loan.id() > after).limit(chunk.getPageSize()).toList();
        });
        LoanSaga failed = LoanSaga.builder().id(7L).loanId(99L).currentState(LoanSagaState.DISBURSEMENT_FAILED).build();
        when(sagaRepository.findByCurrentStateAndIdGreaterThanOrderByIdAsc(eq(LoanSagaState.DISBURSEMENT_FAILED),
                eq(0L), any())).thenReturn(List.of(failed));

        new LoanSagaOrchestrator(sagaRepository, transitionService).reconcile();

        for (long id = 1; id <= 150; id++) {
            verify(transitionService).reconcileLoan(id);
        }
        verify(sagaRepository, times(2)).findByLoanIdIn(any());
        verify(transitionService).compensate(99L);
    }
}
