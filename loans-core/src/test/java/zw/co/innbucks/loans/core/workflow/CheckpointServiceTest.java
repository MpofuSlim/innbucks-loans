package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.CreditDecisionLog;
import zw.co.innbucks.loans.core.loan.CreditReasonCode;
import zw.co.innbucks.loans.core.loan.CreditReasonCodeRepository;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Clearing a loan at a checkpoint, or declining it there as a credit rejection (FR-SSB-014). */
class CheckpointServiceTest {

    private static final LocalDateTime ENTERED = WorkflowFixtures.CHECKPOINT_ACTIVE_SINCE;
    private static final Map<String, CreditReasonCode> REASON_CODES = Map.of(
            "REJECT_IDENTITY", CreditReasonCode.builder().code("REJECT_IDENTITY")
                    .decision(InternalApprovalStatus.REJECTED).active(true).build(),
            "APPROVE_WITHIN_POLICY", CreditReasonCode.builder().code("APPROVE_WITHIN_POLICY")
                    .decision(InternalApprovalStatus.APPROVED).active(true).build(),
            "REJECT_OLD", CreditReasonCode.builder().code("REJECT_OLD")
                    .decision(InternalApprovalStatus.REJECTED).active(false).build());

    private WorkflowStage stage;
    private Loan loan;
    private StageQueue queue;
    private CheckpointDecisionRepository decisions;
    private WorkAssignmentGuard guard;
    private LoanRepository loanRepository;
    private CreditDecisionLog creditDecisionLog;
    private DeductionCancellationService deductionCancellationService;
    private LoanNotificationService loanNotificationService;
    private AuthService authService;
    private AuditService auditService;
    private CheckpointService service;

    @BeforeEach
    void setUp() {
        stage = WorkflowFixtures.checkpoint("HIGH_VALUE_PAYOUT_CHECK", HoldPoint.BEFORE_BOOKING);
        loan = WorkflowFixtures.loan(61, "tmoyo");
        WorkflowStageService stageService = mock(WorkflowStageService.class);
        when(stageService.stage("HIGH_VALUE_PAYOUT_CHECK")).thenReturn(stage);
        when(stageService.stage("CREDIT_DECISION"))
                .thenReturn(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL));
        queue = mock(StageQueue.class);
        when(queue.enteredAt(loan)).thenReturn(Optional.of(ENTERED));
        StageQueues queues = mock(StageQueues.class);
        when(queues.of(stage)).thenReturn(queue);
        decisions = mock(CheckpointDecisionRepository.class);
        when(decisions.save(any())).thenAnswer(i -> i.getArgument(0));
        guard = mock(WorkAssignmentGuard.class);
        loanRepository = mock(LoanRepository.class);
        when(loanRepository.findByIdForUpdate(61L)).thenReturn(Optional.of(loan));
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        CreditReasonCodeRepository reasonCodes = mock(CreditReasonCodeRepository.class);
        when(reasonCodes.findById(anyString()))
                .thenAnswer(i -> Optional.ofNullable(REASON_CODES.get(i.<String>getArgument(0))));
        creditDecisionLog = mock(CreditDecisionLog.class);
        deductionCancellationService = mock(DeductionCancellationService.class);
        loanNotificationService = mock(LoanNotificationService.class);
        authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("finance1");
        when(authService.getLoggedInUser()).thenReturn(WorkflowFixtures.user("finance1", UserGroup.FINANCE));
        auditService = mock(AuditService.class);
        service = new CheckpointService(stageService, mock(WorkflowStageRepository.class), queues, decisions,
                mock(WorkItemRepository.class), guard, loanRepository, reasonCodes, creditDecisionLog,
                deductionCancellationService, loanNotificationService, authService, auditService);
    }

    private static CheckpointDecisionRequest clear() {
        return CheckpointDecisionRequest.builder().outcome(CheckpointOutcome.CLEARED)
                .comment(" Payout wallet confirmed with the applicant by phone ").build();
    }

    private static CheckpointDecisionRequest decline(String reasonCode) {
        return CheckpointDecisionRequest.builder().outcome(CheckpointOutcome.DECLINED).reasonCode(reasonCode)
                .comment("The payout wallet is registered to someone other than the applicant").build();
    }

    @Test
    @DisplayName("clearing records the decision with its wait and is audited; the loan itself is untouched")
    void cleared() {
        CheckpointDecisionResponse cleared = service.decide("HIGH_VALUE_PAYOUT_CHECK", 61L, clear());

        assertThat(cleared.outcome()).isEqualTo(CheckpointOutcome.CLEARED);
        assertThat(cleared.enteredAt()).isEqualTo(ENTERED);
        assertThat(cleared.comment()).isEqualTo("Payout wallet confirmed with the applicant by phone");
        assertThat(cleared.decidedBy()).isEqualTo("finance1");
        assertThat(cleared.reference()).isEqualTo("000000061");
        assertThat(cleared.holdPoint()).isEqualTo(HoldPoint.BEFORE_BOOKING);
        assertThat(cleared.reasonCode()).isNull();
        verify(guard).requireMayAct(stage, loan, "finance1");
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(creditDecisionLog, loanNotificationService, deductionCancellationService);
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        AuditLog row = audit.getValue().build();
        assertThat(row.getEventType()).isEqualTo("CHECKPOINT_CLEARED");
        assertThat(row.getDetail()).isEqualTo("stage=HIGH_VALUE_PAYOUT_CHECK holdPoint=BEFORE_BOOKING");
    }

    @Test
    @DisplayName("declining is a credit rejection: logged, the lodged deduction flagged for cancellation, applicant"
            + " told")
    void declined() {
        loan.setInternalApprovalStatus(InternalApprovalStatus.APPROVED);
        loan.setBatchNumber("B-20260930-1");

        CheckpointDecisionResponse declined =
                service.decide("HIGH_VALUE_PAYOUT_CHECK", 61L, decline("reject_identity"));

        assertThat(declined.outcome()).isEqualTo(CheckpointOutcome.DECLINED);
        assertThat(declined.reasonCode()).isEqualTo("REJECT_IDENTITY");
        assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
        assertThat(loan.getInternalApprovalBy()).isEqualTo("finance1");
        assertThat(loan.getInternalApprovalReasonCode()).isEqualTo("REJECT_IDENTITY");
        assertThat(loan.getInternalApprovalComment()).isEqualTo(
                "High-value payout check: The payout wallet is registered to someone other than the applicant");
        verify(deductionCancellationService).markRequired(loan, DeductionCancellationService.REASON_CREDIT_REJECTED,
                "finance1", "admin-portal");
        verify(creditDecisionLog).record(eq(loan), eq(CreditAction.REJECTED), eq("REJECT_IDENTITY"),
                eq(loan.getInternalApprovalComment()), eq("finance1"), any());
        verify(loanNotificationService).notify(loan, LoanNotice.DECLINED);
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        assertThat(audit.getValue().build().getEventType()).isEqualTo("CHECKPOINT_DECLINED");
    }

    @Test
    @DisplayName("a decline before lodgement leaves nothing to cancel with SSB")
    void declinedBeforeLodgement() {
        service.decide("HIGH_VALUE_PAYOUT_CHECK", 61L, decline("REJECT_IDENTITY"));

        verifyNoInteractions(deductionCancellationService);
        verify(loanNotificationService).notify(loan, LoanNotice.DECLINED);
    }

    @Test
    @DisplayName("a decline needs an active reason code for rejections; nothing is recorded without one")
    void declineNeedsARejectionReason() {
        assertThatThrownBy(() -> service.decide("HIGH_VALUE_PAYOUT_CHECK", 61L, decline(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A reason code is required to decline");
        assertThatThrownBy(() -> service.decide("HIGH_VALUE_PAYOUT_CHECK", 61L, decline("APPROVE_WITHIN_POLICY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reason code APPROVE_WITHIN_POLICY is for APPROVED decisions, not a decline");
        assertThatThrownBy(() -> service.decide("HIGH_VALUE_PAYOUT_CHECK", 61L, decline("REJECT_OLD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown reason code REJECT_OLD");
        verify(decisions, never()).save(any());
        assertThat(loan.getInternalApprovalStatus()).isNull();
    }

    @Test
    @DisplayName("a loan the checkpoint is not holding cannot be decided there")
    void notHeld() {
        when(queue.enteredAt(loan)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide("HIGH_VALUE_PAYOUT_CHECK", 61L, clear()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Loan 000000061 is not waiting at High-value payout check");
        verify(decisions, never()).save(any());
    }

    @Test
    @DisplayName("a system stage is not a checkpoint, and a missing loan is not found")
    void notFound() {
        assertThatThrownBy(() -> service.decide("CREDIT_DECISION", 61L, clear()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No checkpoint stage CREDIT_DECISION");
        assertThatThrownBy(() -> service.decide("HIGH_VALUE_PAYOUT_CHECK", 62L, clear()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 62 not found");
    }

    @Test
    @DisplayName("neither the loan's originator nor a party to it may decide it")
    void segregationOfDuties() {
        when(authService.getLoggedInUsername()).thenReturn("tmoyo");
        assertThatThrownBy(() -> service.decide("HIGH_VALUE_PAYOUT_CHECK", 61L, clear()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Loan 000000061 was originated by tmoyo, who cannot also decide its High-value payout"
                        + " check; someone else must");

        when(authService.getLoggedInUsername()).thenReturn("finance1");
        User party = WorkflowFixtures.user("finance1", UserGroup.FINANCE);
        party.setIdNumber(loan.getNationalIdNumber());
        when(authService.getLoggedInUser()).thenReturn(party);
        assertThatThrownBy(() -> service.decide("HIGH_VALUE_PAYOUT_CHECK", 61L, clear()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("finance1 is a party to loan 000000061 and cannot decide its High-value payout check;"
                        + " someone else must");
        verify(decisions, never()).save(any());
    }

    @Test
    @DisplayName("at an EXCLUSIVE checkpoint, someone else's item is refused before anything is recorded")
    void assignedElsewhere() {
        doThrow(new ConflictException("assigned to finance2")).when(guard).requireMayAct(stage, loan, "finance1");

        assertThatThrownBy(() -> service.decide("HIGH_VALUE_PAYOUT_CHECK", 61L, clear()))
                .isInstanceOf(ConflictException.class);
        verify(decisions, never()).save(any());
    }

    @Test
    @DisplayName("a loan's checkpoints: those holding it now, then how it left each one it passed")
    void forLoan() {
        WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
        WorkflowStage lodgement = WorkflowFixtures.checkpoint("AGENT_APPLICATION_REVIEW", HoldPoint.BEFORE_LODGEMENT);
        lodgement.setName("Agent application review");
        when(stages.findByKindOrderByDisplayOrderAscCodeAsc(StageKind.CHECKPOINT))
                .thenReturn(List.of(lodgement, stage));
        StageQueue lodgementQueue = mock(StageQueue.class);
        when(lodgementQueue.enteredAt(loan)).thenReturn(Optional.empty());
        StageQueues queues = mock(StageQueues.class);
        when(queues.of(stage)).thenReturn(queue);
        when(queues.of(lodgement)).thenReturn(lodgementQueue);
        WorkItemRepository items = mock(WorkItemRepository.class);
        when(items.findByStageCodeAndLoanIdAndEnteredAt("HIGH_VALUE_PAYOUT_CHECK", 61L, ENTERED)).thenReturn(
                Optional.of(WorkItem.builder().assignedTo("finance1").build()));
        when(loanRepository.findById(61L)).thenReturn(Optional.of(loan));
        when(decisions.findByLoanIdOrderByIdAsc(61L)).thenReturn(List.of(CheckpointDecision.builder()
                .stageCode("AGENT_APPLICATION_REVIEW").loanId(61L).enteredAt(ENTERED.minusDays(3))
                .outcome(CheckpointOutcome.CLEARED).comment("Checked").decidedBy("cmanager")
                .decidedAt(ENTERED.minusDays(3).plusHours(1)).build()));
        CheckpointService reader = new CheckpointService(mock(WorkflowStageService.class), stages, queues, decisions,
                items, guard, loanRepository, mock(CreditReasonCodeRepository.class), creditDecisionLog,
                deductionCancellationService, loanNotificationService, authService, auditService);

        List<LoanCheckpointResponse> checkpoints = reader.forLoan(61L);

        assertThat(checkpoints).extracting(LoanCheckpointResponse::stage, LoanCheckpointResponse::status,
                LoanCheckpointResponse::assignedTo).containsExactly(
                tuple("HIGH_VALUE_PAYOUT_CHECK", CheckpointStatus.PENDING, "finance1"),
                tuple("AGENT_APPLICATION_REVIEW", CheckpointStatus.CLEARED, null));
        assertThat(checkpoints.get(1).name()).isEqualTo("Agent application review");
        assertThat(checkpoints.get(1).holdPoint()).isEqualTo(HoldPoint.BEFORE_LODGEMENT);
        assertThatThrownBy(() -> reader.forLoan(62L)).isInstanceOf(NotFoundException.class);
    }
}
