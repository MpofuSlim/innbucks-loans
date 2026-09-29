package zw.co.reikan.loans.core.ndasenda;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.disbursements.LoanAccountCreationJob;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.ledger.LedgerEntryRepository;
import zw.co.reikan.loans.core.ledger.LedgerService;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.DeductionCancellationStatus;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanBatchService;
import zw.co.reikan.loans.core.loan.LoanPublicReferenceService;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;
import zw.co.reikan.loans.core.saga.LoanSaga;
import zw.co.reikan.loans.core.saga.LoanSagaRepository;
import zw.co.reikan.loans.core.saga.LoanSagaState;
import zw.co.reikan.loans.core.saga.LoanSagaTransitionService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The path the saga-only hook missed. A lodgement LoanApprovalServiceJob marked FAILED is flagged
 * LODGEMENT_FAILED and its saga goes SSB_VERIFICATION_FAILED, which is terminal. Ndasenda then
 * accepts it: the loan is revived to APPROVED and the flag withdrawn. When its InnBucks booking
 * later fails, the saga returns at its terminal check and never compensates, so the flag has to
 * come from the booking job itself.
 */
class RevivedLodgementBookingFailureTest {

    @Test
    @DisplayName("a FAILED lodgement revived by SUCCESS whose booking then fails ends REQUIRED, despite its terminal saga")
    void revivedLodgementWhoseBookingFailsIsFlagged() {
        LoanRepository loanRepository = mock(LoanRepository.class);
        AuditService auditService = mock(AuditService.class);
        DeductionCancellationService cancellations =
                new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class));

        Loan loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.FAILED)
                .batchNumber("BATCH-20260901-07")
                .ecNumber("1234567A")
                .mobileNumber("0772123123")
                .disbursedAmount(new BigDecimal("500.00"))
                .grossedMonthlyDeduction(new BigDecimal("98.50"))
                .deductionCancellationStatus(DeductionCancellationStatus.REQUIRED)
                .deductionCancellationReason(DeductionCancellationService.REASON_LODGEMENT_FAILED)
                .deductionCancellationRequestedAt(LocalDateTime.now().minusHours(1))
                .build();
        loan.setId(42L);
        when(loanRepository.findById(42L)).thenReturn(Optional.of(loan));

        LoanSagaRepository sagaRepository = mock(LoanSagaRepository.class);
        LoanSaga saga = LoanSaga.builder().id(1L).loanId(42L).currentState(LoanSagaState.SSB_VERIFICATION_FAILED)
                .createdAt(LocalDateTime.now()).lastTransitionAt(LocalDateTime.now()).build();
        when(sagaRepository.findByLoanId(42L)).thenReturn(Optional.of(saga));
        LedgerEntryRepository ledgerEntryRepository = mock(LedgerEntryRepository.class);
        LoanSagaTransitionService sagaTransitions = new LoanSagaTransitionService(loanRepository, sagaRepository,
                mock(LedgerService.class), ledgerEntryRepository, auditService,
                mock(LoanPublicReferenceService.class), cancellations);

        // 1. Ndasenda accepts the lodgement we had given up on: revived, provisional flag withdrawn.
        NdasendaLoanApprovalServiceImpl ndasenda = new NdasendaLoanApprovalServiceImpl(mock(RestTemplate.class),
                mock(NdasendaAuthServiceImpl.class), mock(NdasendaParameters.class), loanRepository,
                mock(LoanBatchService.class), mock(NotificationService.class), auditService, cancellations);
        ndasenda.processDeductionRequestResponse("BATCH-20260901-07", NdasendaDeduction.builder()
                .id("ND-7002").reference("000000042").status(NdasendaDeductionStatus.SUCCESS)
                .ecNumber("1234567A").build());

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.PENDING);
        assertThat(loan.getDeductionCancellationStatus()).isNull();

        // 2. Credit approves; 3. InnBucks refuses the booking.
        loan.setInternalApprovalStatus(InternalApprovalStatus.APPROVED);
        DisbursementService disbursementService = mock(DisbursementService.class);
        when(disbursementService.createLoanAccount(loan)).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        when(loanRepository.findByLoanApprovalStatusAndInternalApprovalStatusAndLoanAccountStatus(
                LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED, LoanAccountStatus.PENDING))
                .thenReturn(List.of(loan));
        new LoanAccountCreationJob(disbursementService, loanRepository, mock(NotificationService.class),
                cancellations).processLoanAccountCreation();

        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.FAILED);
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("BOOKING_FAILED");

        // 4. The saga cannot help: it is terminal and stays so, and touches nothing.
        sagaTransitions.reconcileLoan(42L);
        assertThat(saga.getCurrentState()).isEqualTo(LoanSagaState.SSB_VERIFICATION_FAILED);
        verifyNoInteractions(ledgerEntryRepository);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeastOnce()).record(captor.capture());
        assertThat(captor.getAllValues().stream().map(b -> b.build().getEventType()))
                .containsExactly("DEDUCTION_CANCELLATION_WITHDRAWN", "DEDUCTION_CANCELLATION_REQUIRED");
        verify(loanRepository, atLeastOnce()).save(any());
    }
}
