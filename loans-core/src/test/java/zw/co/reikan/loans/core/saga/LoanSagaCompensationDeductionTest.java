package zw.co.reikan.loans.core.saga;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.ledger.LedgerEntryRepository;
import zw.co.reikan.loans.core.ledger.LedgerService;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.DeductionCancellationStatus;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanPublicReferenceService;
import zw.co.reikan.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A definitive booking failure ends at the saga's compensation, which is the one place that records
 * that outcome; the payroll deduction lodged before credit is still live there, so it is flagged
 * for cancellation — once, whichever path (first pass or retry) runs the compensation.
 */
class LoanSagaCompensationDeductionTest {

    private LoanRepository loanRepository;
    private LoanSagaRepository sagaRepository;
    private AuditService auditService;
    private LoanSagaTransitionService service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        sagaRepository = mock(LoanSagaRepository.class);
        auditService = mock(AuditService.class);
        LedgerEntryRepository ledgerEntryRepository = mock(LedgerEntryRepository.class);
        when(ledgerEntryRepository.existsByTransactionRef(any())).thenReturn(false);
        service = new LoanSagaTransitionService(loanRepository, sagaRepository, mock(LedgerService.class),
                ledgerEntryRepository, auditService, mock(LoanPublicReferenceService.class),
                new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class)));

        loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .loanAccountStatus(LoanAccountStatus.FAILED)
                .disbursementStatus(LoanDisbursementStatus.FAILED)
                .disbursementStatusMessage("InnBucks loan application failed: HTTP 400 Invalid idNumber")
                .batchNumber("BATCH-20260901-07")
                .approvalReference("ND-7002")
                .ecNumber("1234567A")
                .build();
        loan.setId(42L);
        when(loanRepository.findById(42L)).thenReturn(Optional.of(loan));
    }

    private LoanSaga saga(LoanSagaState state) {
        LoanSaga saga = LoanSaga.builder().id(1L).loanId(42L).currentState(state)
                .createdAt(LocalDateTime.now()).lastTransitionAt(LocalDateTime.now()).build();
        when(sagaRepository.findByLoanId(42L)).thenReturn(Optional.of(saga));
        return saga;
    }

    private AuditLog requiredAuditedOnce() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(captor.capture());
        return captor.getValue().build();
    }

    @Test
    @DisplayName("a booking failure that the saga compensates flags the deduction for cancellation")
    void compensationFlagsDeductionCancellation() {
        LoanSaga saga = saga(LoanSagaState.CREDIT_APPROVED);

        service.reconcileLoan(42L);

        assertThat(saga.getCurrentState()).isEqualTo(LoanSagaState.COMPENSATED);
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("BOOKING_FAILED");
        verify(loanRepository).save(loan);
        AuditLog audit = requiredAuditedOnce();
        assertThat(audit.getEventType()).isEqualTo("DEDUCTION_CANCELLATION_REQUIRED");
        assertThat(audit.getActorId()).isEqualTo("saga-orchestrator");
        assertThat(audit.getCorrelationId()).isEqualTo("BATCH-20260901-07");
    }

    @Test
    @DisplayName("the compensation retry path flags it too")
    void compensationRetryFlagsDeductionCancellation() {
        LoanSaga saga = saga(LoanSagaState.DISBURSEMENT_FAILED);

        service.compensate(42L);

        assertThat(saga.getCurrentState()).isEqualTo(LoanSagaState.COMPENSATED);
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        verify(loanRepository).save(loan);
        assertThat(requiredAuditedOnce().getDetail()).contains("reason=BOOKING_FAILED");
    }

    @Test
    @DisplayName("a retry over a loan already flagged neither re-flags nor re-saves it")
    void compensationRetryIsIdempotent() {
        saga(LoanSagaState.DISBURSEMENT_FAILED);
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.REQUIRED);
        loan.setDeductionCancellationReason("BOOKING_FAILED");

        service.compensate(42L);

        verify(loanRepository, never()).save(any());
        verify(auditService, never()).record(any());
    }
}
