package zw.co.reikan.loans.core.ndasenda;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.DeductionCancellationStatus;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanBatchService;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A deduction response Ndasenda returns but that no loan here accounts for is a stop order on
 * someone's salary our books do not know about. It used to be dropped (a WARN for a bad reference,
 * nothing at all for an unknown loan, a message-less ERROR for a crash); it is now an ERROR plus an
 * audit row keyed on the Ndasenda deduction id and batch. The matched path is pinned unchanged.
 */
class NdasendaDeductionResponseProcessingTest {

    private static final String BATCH = "BATCH-20260923-01";
    private static final String EC_NUMBER = "1234567A";
    private static final String NATIONAL_ID = "63-1234567A63";

    private RestTemplate restTemplate;
    private NdasendaParameters props;
    private LoanRepository loanRepository;
    private NotificationService notificationService;
    private AuditService auditService;
    private NdasendaLoanApprovalServiceImpl service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        props = mock(NdasendaParameters.class);
        loanRepository = mock(LoanRepository.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        service = new NdasendaLoanApprovalServiceImpl(restTemplate, mock(NdasendaAuthServiceImpl.class), props,
                loanRepository, mock(LoanBatchService.class), notificationService, auditService,
                new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class)));
    }

    private static NdasendaDeduction deduction(String id, String reference, NdasendaDeductionStatus status) {
        return NdasendaDeduction.builder()
                .id(id)
                .reference(reference)
                .status(status)
                .ecNumber(EC_NUMBER)
                .idNumber(NATIONAL_ID)
                .message("Insufficient net salary")
                .build();
    }

    private Loan loan(LoanApprovalStatus status) {
        Loan loan = Loan.builder()
                .loanApprovalStatus(status)
                .mobileNumber("0772123123")
                .disbursedAmount(new BigDecimal("500.00"))
                .build();
        loan.setId(42L);
        when(loanRepository.findById(42L)).thenReturn(Optional.of(loan));
        return loan;
    }

    private AuditLog auditedOnce() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(captor.capture());
        return captor.getValue().build();
    }

    @Test
    @DisplayName("a non-numeric reference is audited as UNMATCHED, and no loan is looked up or saved")
    void invalidReferenceIsAuditedAndNoLoanIsTouched() {
        service.processDeductionRequestResponse(BATCH, deduction("ND-9001", "LN-ABC", NdasendaDeductionStatus.SUCCESS));

        AuditLog audit = auditedOnce();
        assertThat(audit.getEventType()).isEqualTo("NDASENDA_RESPONSE_UNMATCHED");
        assertThat(audit.getEntityType()).isEqualTo("NDASENDA_DEDUCTION");
        assertThat(audit.getEntityId()).isEqualTo("ND-9001");
        assertThat(audit.getCorrelationId()).isEqualTo(BATCH);
        assertThat(audit.getActorId()).isEqualTo("ndasenda-response-job");
        assertThat(audit.getDetail())
                .contains("reason=invalid_reference", "reference=LN-ABC", "status=SUCCESS", "ecNumber=*****67A")
                .doesNotContain(EC_NUMBER, NATIONAL_ID);
        assertThat(audit.getPayloadHash()).hasSize(64);
        verify(loanRepository, never()).findById(anyLong());
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a null reference is the invalid-reference case, not a crash")
    void nullReferenceIsInvalid() {
        service.processDeductionRequestResponse(BATCH, deduction("ND-9002", null, NdasendaDeductionStatus.FAILED));

        assertThat(auditedOnce().getDetail()).contains("reason=invalid_reference");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("a numeric reference with no loan is audited as UNMATCHED instead of vanishing")
    void unknownLoanIsAudited() {
        when(loanRepository.findById(99L)).thenReturn(Optional.empty());

        service.processDeductionRequestResponse(BATCH, deduction("ND-9003", "000000099", NdasendaDeductionStatus.SUCCESS));

        AuditLog audit = auditedOnce();
        assertThat(audit.getEventType()).isEqualTo("NDASENDA_RESPONSE_UNMATCHED");
        assertThat(audit.getEntityId()).isEqualTo("ND-9003");
        assertThat(audit.getCorrelationId()).isEqualTo(BATCH);
        assertThat(audit.getDetail())
                .contains("reason=unknown_loan", "reference=000000099")
                .doesNotContain(EC_NUMBER, NATIONAL_ID);
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a processing failure is audited as FAILED with the cause, and does not propagate")
    void processingFailureIsAudited() {
        when(loanRepository.findById(42L)).thenThrow(new IllegalStateException("connection reset"));

        assertThatCode(() -> service.processDeductionRequestResponse(BATCH,
                deduction("ND-9004", "000000042", NdasendaDeductionStatus.SUCCESS))).doesNotThrowAnyException();

        AuditLog audit = auditedOnce();
        assertThat(audit.getEventType()).isEqualTo("NDASENDA_RESPONSE_FAILED");
        assertThat(audit.getEntityId()).isEqualTo("ND-9004");
        assertThat(audit.getCorrelationId()).isEqualTo(BATCH);
        assertThat(audit.getDetail()).contains("error=connection reset").doesNotContain(EC_NUMBER, NATIONAL_ID);
    }

    @Test
    @DisplayName("an audit that throws cannot stop the batch — the deduction is still reported, not raised")
    void auditFailureDoesNotPropagate() {
        doThrow(new IllegalStateException("could not open transaction")).when(auditService).record(any());

        assertThatCode(() -> service.processDeductionRequestResponse(BATCH,
                deduction("ND-9005", "LN-ABC", NdasendaDeductionStatus.SUCCESS))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("matched rejection: loan updated, SMS sent and saved exactly as before — nothing audited")
    void matchedRejectionIsUnchanged() {
        Loan loan = loan(LoanApprovalStatus.PROCESSING);

        service.processDeductionRequestResponse(BATCH, deduction("ND-7001", "000000042", NdasendaDeductionStatus.FAILED));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-7001");
        assertThat(loan.getDateApproved()).isNotNull();
        assertThat(loan.getLoanAccountStatus()).isNull();
        verify(notificationService).sendSms("0772123123",
                "We regret to inform you that your loan application with ref # 000000042 has been declined. "
                        + "Insufficient net salary. Contact Innbucks for more Info");
        verify(loanRepository).save(loan);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("matched approval: queued for disbursement with no SMS, as before — nothing audited")
    void matchedApprovalIsUnchanged() {
        Loan loan = loan(LoanApprovalStatus.PROCESSING);

        service.processDeductionRequestResponse(BATCH, deduction("ND-7002", "000000042", NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-7002");
        assertThat(loan.getDateApproved()).isNotNull();
        assertThat(loan.getDisbursementAttempts()).isZero();
        assertThat(loan.getNextDisbursementAttemptDate()).isNotNull();
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.PENDING);
        verifyNoInteractions(notificationService);
        verify(loanRepository).save(loan);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("matched but already at that status: left alone, as before — nothing audited")
    void matchedAlreadyUpdatedIsUnchanged() {
        loan(LoanApprovalStatus.APPROVED);

        service.processDeductionRequestResponse(BATCH, deduction("ND-7003", "000000042", NdasendaDeductionStatus.SUCCESS));

        verify(loanRepository, never()).save(any());
        verifyNoInteractions(notificationService, auditService);
    }

    // --- rewind guard ------------------------------------------------------------------------

    private Loan disbursedLoan() {
        Loan loan = loan(LoanApprovalStatus.APPROVED);
        loan.setBatchNumber("BATCH-20260901-07");
        loan.setApprovalReference("ND-1000");
        loan.setInternalApprovalStatus(InternalApprovalStatus.APPROVED);
        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        loan.setDisbursementAttempts(1);
        return loan;
    }

    @Test
    @DisplayName("rewind guard: an APPROVED response for a booked and paid loan changes nothing and sends no SMS")
    void approvalForPaidLoanChangesNothing() {
        Loan loan = disbursedLoan();

        service.processDeductionRequestResponse(BATCH, deduction("ND-7004", "000000042", NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-1000");
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.CREATED);
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.SUCCESS);
        assertThat(loan.getDisbursementAttempts()).isEqualTo(1);
        assertThat(loan.getNextDisbursementAttemptDate()).isNull();
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(notificationService, auditService);
    }

    @Test
    @DisplayName("rewind guard: a FAILED response for a disbursed loan changes nothing, sends no SMS, and is a CONFLICT")
    void rejectionForDisbursedLoanIsAConflict() {
        Loan loan = disbursedLoan();

        service.processDeductionRequestResponse(BATCH, deduction("ND-7005", "000000042", NdasendaDeductionStatus.FAILED));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-1000");
        assertThat(loan.getDateApproved()).isNull();
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.CREATED);
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.SUCCESS);
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(notificationService);

        AuditLog audit = auditedOnce();
        assertThat(audit.getEventType()).isEqualTo("NDASENDA_RESPONSE_CONFLICT");
        assertThat(audit.getEntityType()).isEqualTo("NDASENDA_DEDUCTION");
        assertThat(audit.getEntityId()).isEqualTo("ND-7005");
        assertThat(audit.getCorrelationId()).isEqualTo(BATCH);
        assertThat(audit.getDetail())
                .contains("loanId=42", "loanStatus=APPROVED", "accountStatus=CREATED", "disbursementStatus=SUCCESS",
                        "status=FAILED", "ecNumber=*****67A")
                .doesNotContain(EC_NUMBER, NATIONAL_ID);
    }

    @Test
    @DisplayName("rewind guard: a SUCCESS cannot flip a declined loan to APPROVED — the live deduction is flagged instead")
    void approvalForDeclinedLoanFlagsCancellationInsteadOfFlipping() {
        Loan loan = loan(LoanApprovalStatus.REJECTED);
        loan.setBatchNumber("BATCH-20260901-07");
        loan.setApprovalReference("ND-7001");

        service.processDeductionRequestResponse(BATCH, deduction("ND-7006", "000000042", NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-7001");
        assertThat(loan.getLoanAccountStatus()).isNull();
        assertThat(loan.getNextDisbursementAttemptDate()).isNull();
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("ACCEPTED_AFTER_CLOSE");
        verify(loanRepository).save(loan);
        verifyNoInteractions(notificationService);

        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, times(2)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(b -> b.build().getEventType())
                .containsExactly("NDASENDA_RESPONSE_CONFLICT", "DEDUCTION_CANCELLATION_REQUIRED");
    }

    @Test
    @DisplayName("rewind guard: a FAILED loan with no lodgement reference is not awaiting Ndasenda")
    void failedLoanWithoutLodgementIsNotRevived() {
        Loan loan = loan(LoanApprovalStatus.FAILED);

        service.processDeductionRequestResponse(BATCH, deduction("ND-7007", "000000042", NdasendaDeductionStatus.FAILED));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.FAILED);
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(notificationService);
        assertThat(auditedOnce().getEventType()).isEqualTo("NDASENDA_RESPONSE_CONFLICT");
    }

    @Test
    @DisplayName("a SUCCESS for a FAILED loan we never saw reach Ndasenda (e.g. a timed-out POST) stays FAILED, flagged")
    void acceptanceOfUnrecordedLodgementIsFlagged() {
        Loan loan = loan(LoanApprovalStatus.FAILED);

        service.processDeductionRequestResponse(BATCH, deduction("ND-7012", "000000042", NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.FAILED);
        assertThat(loan.getApprovalReference()).isNull();
        assertThat(loan.getLoanAccountStatus()).isNull();
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("ACCEPTED_AFTER_CLOSE");
        verify(loanRepository).save(loan);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a FAILED loan whose lodgement reached Ndasenda takes its answer, and the provisional flag is withdrawn")
    void ambiguousLodgementTakesTheAnswerAndWithdrawsTheFlag() {
        Loan loan = loan(LoanApprovalStatus.FAILED);
        loan.setBatchNumber("BATCH-20260901-07");
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.REQUIRED);
        loan.setDeductionCancellationReason("LODGEMENT_FAILED");

        service.processDeductionRequestResponse(BATCH, deduction("ND-7008", "000000042", NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-7008");
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.PENDING);
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        assertThat(loan.getDeductionCancellationReason()).isNull();
        verify(loanRepository).save(loan);
        verifyNoInteractions(notificationService);
        AuditLog audit = auditedOnce();
        assertThat(audit.getEventType()).isEqualTo("DEDUCTION_CANCELLATION_WITHDRAWN");
        assertThat(audit.getDetail()).contains("reason=LODGEMENT_FAILED", "ndasendaOutcome=SUCCESS");
    }

    @Test
    @DisplayName("a FAILED lodgement already cancelled on Ndasenda's portal is never revived by a late SUCCESS")
    void cancelledLodgementIsNotRevived() {
        Loan loan = loan(LoanApprovalStatus.FAILED);
        loan.setBatchNumber("BATCH-20260901-07");
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.CANCELLED_EXTERNALLY);

        service.processDeductionRequestResponse(BATCH, deduction("ND-7009", "000000042", NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.FAILED);
        assertThat(loan.getLoanAccountStatus()).isNull();
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.CANCELLED_EXTERNALLY);
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(notificationService);
        assertThat(auditedOnce().getEventType()).isEqualTo("NDASENDA_RESPONSE_CONFLICT");
    }

    @Test
    @DisplayName("a duplicate-lodgement FAILED record after the SUCCESS was applied leaves the approval in place")
    void secondRecordForSameReferenceCannotFlipTheFirst() {
        Loan loan = loan(LoanApprovalStatus.PROCESSING);
        loan.setBatchNumber(BATCH);

        service.processDeductionRequestResponse(BATCH, deduction("ND-7010", "000000042", NdasendaDeductionStatus.SUCCESS));
        service.processDeductionRequestResponse(BATCH, deduction("ND-7011", "000000042", NdasendaDeductionStatus.FAILED));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-7010");
        verify(loanRepository, times(1)).save(loan);
        verifyNoInteractions(notificationService);
        assertThat(auditedOnce().getEventType()).isEqualTo("NDASENDA_RESPONSE_CONFLICT");
    }

    @Test
    @DisplayName("the daily run threads the batch id through and carries on past unmatched lines")
    @SuppressWarnings("unchecked")
    void dailyRunThreadsBatchIdAndCarriesOn() {
        when(props.getDeductionResponsesByDateRangeEndpoint()).thenReturn("responses-by-date");
        when(props.getDeductionResponsesByBatchId()).thenReturn("responses-by-batch");
        when(restTemplate.exchange(eq("responses-by-date"), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class), any(Object[].class)))
                .thenReturn(ResponseEntity.ok(List.of(NdasendaDeductionsBatchRequest.builder().id(BATCH).build())));
        when(restTemplate.exchange(eq("responses-by-batch"), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class), any(Object[].class)))
                .thenReturn(ResponseEntity.ok(List.of(NdasendaDeductionsBatchRequest.builder()
                        .id(BATCH)
                        .deductions(List.of(
                                deduction("ND-9001", "LN-ABC", NdasendaDeductionStatus.SUCCESS),
                                deduction("ND-9003", "000000099", NdasendaDeductionStatus.SUCCESS),
                                deduction("ND-7001", "000000042", NdasendaDeductionStatus.FAILED)))
                        .build())));
        when(loanRepository.findById(99L)).thenReturn(Optional.empty());
        Loan loan = loan(LoanApprovalStatus.PROCESSING);

        service.processDeductionResponses(LocalDate.of(2026, 9, 23));

        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, times(2)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(b -> b.build().getEntityId())
                .containsExactly("ND-9001", "ND-9003");
        assertThat(captor.getAllValues()).extracting(b -> b.build().getCorrelationId())
                .containsOnly(BATCH);
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);
        verify(loanRepository).save(loan);
    }

    @Test
    @DisplayName("the EC number keeps only its last 3 characters; a short one is masked whole")
    void ecNumberMasking() {
        assertThat(NdasendaLoanApprovalServiceImpl.maskEcNumber("1234567A")).isEqualTo("*****67A");
        assertThat(NdasendaLoanApprovalServiceImpl.maskEcNumber("67A")).isEqualTo("***");
        assertThat(NdasendaLoanApprovalServiceImpl.maskEcNumber("")).isEmpty();
        assertThat(NdasendaLoanApprovalServiceImpl.maskEcNumber(null)).isNull();
    }
}
