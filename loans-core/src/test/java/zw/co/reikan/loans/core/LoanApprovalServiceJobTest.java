package zw.co.reikan.loans.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.DeductionCancellationStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanBatchService;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.ndasenda.LoanApprovalResponse;
import zw.co.reikan.loans.core.ndasenda.LoanApprovalService;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The lodgement job marks a loan FAILED for ANY exception, including one thrown after Ndasenda
 * accepted the lodgement, and a FAILED loan is never lodged again. Where the batch number proves
 * the lodgement got through, the live deduction is flagged for cancellation.
 */
class LoanApprovalServiceJobTest {

    private LoanApprovalService loanApprovalService;
    private LoanRepository loanRepository;
    private NotificationService notificationService;
    private AuditService auditService;
    private LoanApprovalServiceJob job;
    private Loan loan;

    @BeforeEach
    void setUp() {
        loanApprovalService = mock(LoanApprovalService.class);
        loanRepository = mock(LoanRepository.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        job = new LoanApprovalServiceJob(loanApprovalService, loanRepository, notificationService,
                mock(LoanBatchService.class),
                new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class)));

        loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.NEW)
                .ecNumber("1234567A")
                .nationalIdNumber("63-1234567A63")
                .grossedMonthlyDeduction(new BigDecimal("98.50"))
                .tenor(6)
                .mobileNumber("0772123123")
                .build();
        loan.setId(42L);
        when(loanRepository.findByLoanApprovalStatus(LoanApprovalStatus.NEW)).thenReturn(List.of(loan));
    }

    @Test
    @DisplayName("a failure AFTER the lodgement reached Ndasenda (batch number held) flags the deduction")
    void failureAfterLodgementFlagsCancellation() {
        when(loanApprovalService.requestApproval(any())).thenReturn(LoanApprovalResponse.builder()
                .status(LoanApprovalStatus.PROCESSING)
                .batchNumber("BATCH-20260923-01")
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2027, 3, 31))
                .build());
        doThrow(new IllegalStateException("SMS gateway unreachable"))
                .when(notificationService).sendSms(anyString(), anyString());

        job.processSsbApprovals();

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.FAILED);
        assertThat(loan.getBatchNumber()).isEqualTo("BATCH-20260923-01");
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("LODGEMENT_FAILED");
        verify(loanRepository, atLeastOnce()).save(loan);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(captor.capture());
        AuditLog audit = captor.getValue().build();
        assertThat(audit.getEventType()).isEqualTo("DEDUCTION_CANCELLATION_REQUIRED");
        assertThat(audit.getActorId()).isEqualTo("ssb-approval-job");
        assertThat(audit.getCorrelationId()).isEqualTo("BATCH-20260923-01");
    }

    @Test
    @DisplayName("a lodgement that never reached Ndasenda (no batch number) is FAILED with nothing to cancel")
    void failureBeforeLodgementDoesNotFlag() {
        when(loanApprovalService.requestApproval(any()))
                .thenThrow(new RuntimeException("Failed to request loan approval"));

        job.processSsbApprovals();

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.FAILED);
        assertThat(loan.getBatchNumber()).isNull();
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        verify(loanRepository).save(loan);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("a clean lodgement is PROCESSING with nothing to cancel")
    void cleanLodgementDoesNotFlag() {
        when(loanApprovalService.requestApproval(any())).thenReturn(LoanApprovalResponse.builder()
                .status(LoanApprovalStatus.PROCESSING)
                .batchNumber("BATCH-20260923-01")
                .build());

        job.processSsbApprovals();

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        verifyNoInteractions(auditService);
    }
}
