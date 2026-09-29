package zw.co.reikan.loans.core.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.exception.ConflictException;
import zw.co.reikan.loans.core.exception.NotFoundException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A lodged Ndasenda deduction on a loan that will not be paid is tracked, never sent: flagged
 * REQUIRED once, listed for operators, and closed by an operator recording what they did on
 * Ndasenda's own portal.
 */
class DeductionCancellationServiceTest {

    private static final String EC_NUMBER = "1234567A";

    private LoanRepository loanRepository;
    private AuditService auditService;
    private AuthService authService;
    private DeductionCancellationService service;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        auditService = mock(AuditService.class);
        authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("finance.officer");
        service = new DeductionCancellationService(loanRepository, auditService, authService);
    }

    private static Loan lodgedLoan(long id) {
        Loan loan = Loan.builder()
                .ecNumber(EC_NUMBER)
                .nationalIdNumber("63-1234567A63")
                .batchNumber("BATCH-20260901-07")
                .grossedMonthlyDeduction(new BigDecimal("98.50"))
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .build();
        loan.setId(id);
        return loan;
    }

    private List<AuditLog> audits(int times) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, times(times)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    @Test
    @DisplayName("markRequired flags the loan and audits it with identifiers only")
    void markRequiredFlagsAndAudits() {
        Loan loan = lodgedLoan(42L);

        assertThat(service.markRequired(loan, "CREDIT_REJECTED", "credit.manager", "admin-portal")).isTrue();

        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("CREDIT_REJECTED");
        assertThat(loan.getDeductionCancellationRequestedAt()).isNotNull();
        AuditLog audit = audits(1).get(0);
        assertThat(audit.getEventType()).isEqualTo("DEDUCTION_CANCELLATION_REQUIRED");
        assertThat(audit.getEntityType()).isEqualTo("LOAN");
        assertThat(audit.getEntityId()).isEqualTo("42");
        assertThat(audit.getActorId()).isEqualTo("credit.manager");
        assertThat(audit.getChannelUsed()).isEqualTo("admin-portal");
        assertThat(audit.getCorrelationId()).isEqualTo("BATCH-20260901-07");
        assertThat(audit.getStateTransitionDelta()).isEqualTo("{\"from\":null,\"to\":\"REQUIRED\"}");
        assertThat(audit.getDetail())
                .contains("reason=CREDIT_REJECTED", "reference=000000042", "batch=BATCH-20260901-07",
                        "instalment=98.50", "ecNumber=*****67A")
                .doesNotContain(EC_NUMBER, "63-1234567A63");
        // The flag is the caller's to save, inside its own transaction.
        verifyNoInteractions(loanRepository);
    }

    @Test
    @DisplayName("REQUIRED is idempotent: a second flag keeps the first reason and time and audits nothing")
    void markRequiredIsIdempotent() {
        Loan loan = lodgedLoan(42L);
        service.markRequired(loan, "LODGEMENT_FAILED", "ssb-approval-job", "system");
        LocalDateTime firstRequestedAt = loan.getDeductionCancellationRequestedAt();

        assertThat(service.markRequired(loan, "BOOKING_FAILED", "saga-orchestrator", "system")).isFalse();

        assertThat(loan.getDeductionCancellationReason()).isEqualTo("LODGEMENT_FAILED");
        assertThat(loan.getDeductionCancellationRequestedAt()).isEqualTo(firstRequestedAt);
        audits(1);
    }

    @Test
    @DisplayName("a deduction already recorded as cancelled is never flagged again")
    void cancelledIsNeverReflagged() {
        Loan loan = lodgedLoan(42L);
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.CANCELLED_EXTERNALLY);

        assertThat(service.markRequired(loan, "BOOKING_FAILED", "saga-orchestrator", "system")).isFalse();

        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.CANCELLED_EXTERNALLY);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("an audit that throws cannot undo the flag or fail the caller")
    void auditFailureDoesNotPropagate() {
        doThrow(new IllegalStateException("could not open transaction")).when(auditService).record(any());
        Loan loan = lodgedLoan(42L);

        assertThatCode(() -> service.markRequired(loan, "CREDIT_REJECTED", "credit.manager", "admin-portal"))
                .doesNotThrowAnyException();
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
    }

    @Test
    @DisplayName("a batch number or Ndasenda's own id is the evidence of a lodgement")
    void wasLodged() {
        assertThat(DeductionCancellationService.wasLodged(Loan.builder().batchNumber("B-1").build())).isTrue();
        assertThat(DeductionCancellationService.wasLodged(Loan.builder().approvalReference("ND-1").build())).isTrue();
        assertThat(DeductionCancellationService.wasLodged(Loan.builder().batchNumber(" ").build())).isFalse();
        assertThat(DeductionCancellationService.wasLodged(Loan.builder().build())).isFalse();
    }

    @Test
    @DisplayName("an IN DOUBT flag tells the operator to confirm with InnBucks before cancelling; others say cancel")
    void inDoubtActionSaysConfirmFirst() {
        assertThat(DeductionCancellationService.operatorAction("BOOKING_IN_DOUBT"))
                .contains("customer may hold this loan",
                        "confirm with InnBucks that no loan was booked under this reference before cancelling");
        for (String reason : List.of("CREDIT_REJECTED", "BOOKING_FAILED", "LODGEMENT_FAILED", "ACCEPTED_AFTER_CLOSE")) {
            assertThat(DeductionCancellationService.operatorAction(reason))
                    .as(reason).contains("cancel the deduction").doesNotContain("confirm with InnBucks");
        }
    }

    @Test
    @DisplayName("withdraw clears a REQUIRED flag and audits it; a recorded cancellation is left alone")
    void withdraw() {
        Loan flagged = lodgedLoan(42L);
        service.markRequired(flagged, "LODGEMENT_FAILED", "ssb-approval-job", "system");
        Loan cancelled = lodgedLoan(43L);
        cancelled.setDeductionCancellationStatus(DeductionCancellationStatus.CANCELLED_EXTERNALLY);

        service.withdraw(flagged, "SUCCESS", "ndasenda-response-job");
        service.withdraw(cancelled, "SUCCESS", "ndasenda-response-job");

        assertThat(flagged.getDeductionCancellationStatus()).isNull();
        assertThat(flagged.getDeductionCancellationReason()).isNull();
        assertThat(flagged.getDeductionCancellationRequestedAt()).isNull();
        assertThat(cancelled.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.CANCELLED_EXTERNALLY);
        AuditLog withdrawal = audits(2).get(1);
        assertThat(withdrawal.getEventType()).isEqualTo("DEDUCTION_CANCELLATION_WITHDRAWN");
        assertThat(withdrawal.getEntityId()).isEqualTo("42");
        assertThat(withdrawal.getDetail()).contains("reason=LODGEMENT_FAILED", "ndasendaOutcome=SUCCESS");
    }

    @Test
    @DisplayName("the list asks for REQUIRED loans only, oldest first, and masks the EC number")
    void findRequiredListsOnlyRequired() {
        Loan loan = lodgedLoan(42L);
        loan.setApprovalReference("ND-7002");
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.REQUIRED);
        loan.setDeductionCancellationReason("CREDIT_REJECTED");
        loan.setDeductionCancellationRequestedAt(LocalDateTime.of(2026, 9, 20, 8, 30));
        loan.setDisbursementStatusMessage("InnBucks loan application failed: HTTP 400 Invalid idNumber");
        when(loanRepository.findByDeductionCancellationStatusOrderByDeductionCancellationRequestedAtAscIdAsc(
                DeductionCancellationStatus.REQUIRED)).thenReturn(List.of(loan));

        List<DeductionCancellationDto> required = service.findRequired();

        verify(loanRepository).findByDeductionCancellationStatusOrderByDeductionCancellationRequestedAtAscIdAsc(
                DeductionCancellationStatus.REQUIRED);
        verifyNoMoreInteractions(loanRepository);
        assertThat(required).singleElement().satisfies(dto -> {
            assertThat(dto.getId()).isEqualTo(42L);
            assertThat(dto.getReference()).isEqualTo("000000042");
            assertThat(dto.getEcNumber()).isEqualTo("*****67A");
            assertThat(dto.getInstalmentLodged()).isEqualByComparingTo("98.50");
            assertThat(dto.getBatchNumber()).isEqualTo("BATCH-20260901-07");
            assertThat(dto.getNdasendaDeductionId()).isEqualTo("ND-7002");
            assertThat(dto.getReason()).isEqualTo("CREDIT_REJECTED");
            assertThat(dto.getAction()).contains("cancel the deduction on Ndasenda's portal");
            assertThat(dto.getDisbursementStatusMessage())
                    .isEqualTo("InnBucks loan application failed: HTTP 400 Invalid idNumber");
            assertThat(dto.getRequestedAt()).isEqualTo(LocalDateTime.of(2026, 9, 20, 8, 30));
            assertThat(dto.getStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        });
    }

    @Test
    @DisplayName("recording a cancellation sets who, when and the note under a row lock, and audits the note's hash only")
    void markCancelledExternally() {
        Loan loan = lodgedLoan(42L);
        service.markRequired(loan, "BOOKING_FAILED", "saga-orchestrator", "system");
        when(loanRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DeductionCancellationDto dto = service.markCancelledExternally(42L, "  Cancelled on portal, ref NDC-551  ");

        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.CANCELLED_EXTERNALLY);
        assertThat(loan.getDeductionCancellationNote()).isEqualTo("Cancelled on portal, ref NDC-551");
        assertThat(loan.getDeductionCancelledBy()).isEqualTo("finance.officer");
        assertThat(loan.getDeductionCancelledAt()).isNotNull();
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("BOOKING_FAILED");
        verify(loanRepository).save(loan);
        assertThat(dto.getStatus()).isEqualTo(DeductionCancellationStatus.CANCELLED_EXTERNALLY);
        assertThat(dto.getCancelledBy()).isEqualTo("finance.officer");
        assertThat(dto.getNote()).isEqualTo("Cancelled on portal, ref NDC-551");
        assertThat(dto.getAction()).isNull();

        AuditLog audit = audits(2).get(1);
        assertThat(audit.getEventType()).isEqualTo("DEDUCTION_CANCELLED_EXTERNALLY");
        assertThat(audit.getEntityId()).isEqualTo("42");
        assertThat(audit.getActorId()).isEqualTo("finance.officer");
        assertThat(audit.getStateTransitionDelta())
                .isEqualTo("{\"from\":\"REQUIRED\",\"to\":\"CANCELLED_EXTERNALLY\"}");
        assertThat(audit.getPayloadHash())
                .isEqualTo(AuditService.sha256Hex("Cancelled on portal, ref NDC-551"));
        assertThat(audit.getDetail()).doesNotContain("NDC-551", EC_NUMBER);
    }

    @Test
    @DisplayName("recording a cancellation for a loan with none pending is a 409, and nothing is written")
    void markCancelledExternallyWhenNotRequiredConflicts() {
        Loan loan = lodgedLoan(42L);
        when(loanRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));

        assertThatThrownBy(() -> service.markCancelledExternally(42L, "done"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Loan 42 has no deduction cancellation pending");
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("recording the same cancellation twice is a 409 naming who recorded it first")
    void markCancelledExternallyTwiceConflicts() {
        Loan loan = lodgedLoan(42L);
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.CANCELLED_EXTERNALLY);
        loan.setDeductionCancelledBy("bulkit.admin");
        loan.setDeductionCancelledAt(LocalDateTime.of(2026, 9, 21, 10, 0));
        when(loanRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));

        assertThatThrownBy(() -> service.markCancelledExternally(42L, "done again"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Loan 42's deduction was already recorded as cancelled by bulkit.admin at 2026-09-21T10:00");
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("recording a cancellation for an unknown loan is a 404")
    void markCancelledExternallyUnknownLoan() {
        when(loanRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markCancelledExternally(7L, "done"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 7 not found");
    }
}
