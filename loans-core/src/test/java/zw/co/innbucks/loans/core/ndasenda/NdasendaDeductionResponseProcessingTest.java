package zw.co.innbucks.loans.core.ndasenda;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.DeductionCancellationStatus;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanBatchService;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;

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
    private LoanNotificationService loanNotificationService;
    private AuditService auditService;
    private NdasendaLoanApprovalServiceImpl service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        props = mock(NdasendaParameters.class);
        when(props.getResponses()).thenReturn(new NdasendaParameters.Responses());
        loanRepository = mock(LoanRepository.class);
        loanNotificationService = mock(LoanNotificationService.class);
        auditService = mock(AuditService.class);
        service = new NdasendaLoanApprovalServiceImpl(restTemplate, mock(NdasendaAuthService.class), props,
                loanRepository, mock(LoanBatchService.class), loanNotificationService, auditService,
                new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class)), new MarketTimeZone("ZW"));
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
        verifyNoInteractions(loanNotificationService);
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
        verifyNoInteractions(loanNotificationService);
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
    @DisplayName("matched rejection: loan updated, fixed decline SMS sent, reason kept on the loan — nothing audited")
    void matchedRejectionIsUnchanged() {
        Loan loan = loan(LoanApprovalStatus.PROCESSING);

        service.processDeductionRequestResponse(BATCH, deduction("ND-7001", "000000042", NdasendaDeductionStatus.FAILED));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-7001");
        assertThat(loan.getDateApproved()).isNotNull();
        assertThat(loan.getLoanAccountStatus()).isNull();
        // The customer gets the fixed decline; Ndasenda's reason stays on the loan for staff.
        assertThat(loan.getLoanStatusMessage()).isEqualTo("Insufficient net salary");
        InOrder saveThenTell = inOrder(loanRepository, loanNotificationService);
        saveThenTell.verify(loanRepository).save(loan);
        saveThenTell.verify(loanNotificationService).notify(loan, LoanNotice.DECLINED);
        assertThat(LoanNotice.DECLINED.textFor(loan)).isEqualTo("We regret to inform you that your loan application"
                + " with ref # 000000042 has been declined. Please contact Innbucks for more information.");
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("matched approval: queued for disbursement and the applicant told SSB confirmed the deduction — nothing audited")
    void matchedApprovalIsUnchanged() {
        Loan loan = loan(LoanApprovalStatus.PROCESSING);

        service.processDeductionRequestResponse(BATCH, deduction("ND-7002", "000000042", NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-7002");
        assertThat(loan.getDateApproved()).isNotNull();
        assertThat(loan.getDisbursementAttempts()).isZero();
        assertThat(loan.getNextDisbursementAttemptDate()).isNotNull();
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.PENDING);
        verify(loanNotificationService).notify(loan, LoanNotice.SSB_CONFIRMED);
        verify(loanRepository).save(loan);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("matched but already at that status: left alone, as before — nothing audited")
    void matchedAlreadyUpdatedIsUnchanged() {
        loan(LoanApprovalStatus.APPROVED);

        service.processDeductionRequestResponse(BATCH, deduction("ND-7003", "000000042", NdasendaDeductionStatus.SUCCESS));

        verify(loanRepository, never()).save(any());
        verifyNoInteractions(loanNotificationService, auditService);
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
        verifyNoInteractions(loanNotificationService, auditService);
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
        verifyNoInteractions(loanNotificationService);

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
        verifyNoInteractions(loanNotificationService);

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
        verifyNoInteractions(loanNotificationService);
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
        verifyNoInteractions(loanNotificationService);
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
        verify(loanNotificationService).notify(loan, LoanNotice.SSB_CONFIRMED);
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
        verifyNoInteractions(loanNotificationService);
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
        verify(loanNotificationService).notify(loan, LoanNotice.SSB_CONFIRMED);
        verify(loanNotificationService, never()).notify(loan, LoanNotice.DECLINED);
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
                .thenReturn(ResponseEntity.ok(List.of(NdasendaDeductionBatch.builder().id(BATCH).build())));
        when(restTemplate.exchange(eq("responses-by-batch"), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class), any(Object[].class)))
                .thenReturn(ResponseEntity.ok(List.of(NdasendaDeductionBatch.builder()
                        .id(BATCH)
                        .deductions(List.of(
                                deduction("ND-9001", "LN-ABC", NdasendaDeductionStatus.SUCCESS),
                                deduction("ND-9003", "000000099", NdasendaDeductionStatus.SUCCESS),
                                deduction("ND-7001", "000000042", NdasendaDeductionStatus.FAILED)))
                        .build())));
        when(loanRepository.findById(99L)).thenReturn(Optional.empty());
        Loan loan = loan(LoanApprovalStatus.PROCESSING);

        service.sweepDeductionResponses(LocalDate.of(2026, 9, 23).atTime(10, 0));

        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, times(2)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(b -> b.build().getEntityId())
                .containsExactly("ND-9001", "ND-9003");
        assertThat(captor.getAllValues()).extracting(b -> b.build().getCorrelationId())
                .containsOnly(BATCH);
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);
        verify(loanRepository).save(loan);
    }

    // --- which records may decide a loan ----------------------------------------------------

    private static NdasendaDeduction typed(String id, NdasendaDeductionType type, NdasendaDeductionStatus status) {
        NdasendaDeduction deduction = deduction(id, "000000042", status);
        deduction.setType(type);
        return deduction;
    }

    @Test
    @DisplayName("a CHANGE record's FAILED cannot decline a PROCESSING loan: no SMS, no save, audited IGNORED")
    void changeRecordNeverDeclines() {
        Loan loan = loan(LoanApprovalStatus.PROCESSING);
        loan.setBatchNumber(BATCH);

        service.processDeductionRequestResponse(BATCH, typed("ND-8001", NdasendaDeductionType.CHANGE,
                NdasendaDeductionStatus.FAILED));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(loan.getApprovalReference()).isNull();
        assertThat(loan.getLoanStatusMessage()).isNull();
        verify(loanRepository, never()).findById(anyLong());
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(loanNotificationService);

        AuditLog audit = auditedOnce();
        assertThat(audit.getEventType()).isEqualTo("NDASENDA_RESPONSE_IGNORED");
        assertThat(audit.getEntityType()).isEqualTo("NDASENDA_DEDUCTION");
        assertThat(audit.getEntityId()).isEqualTo("ND-8001");
        assertThat(audit.getCorrelationId()).isEqualTo(BATCH);
        assertThat(audit.getDetail())
                .contains("reason=not_a_lodgement", "type=CHANGE", "reference=000000042", "status=FAILED",
                        "ecNumber=*****67A")
                .doesNotContain(EC_NUMBER, NATIONAL_ID);
    }

    @Test
    @DisplayName("a DELETE record's SUCCESS cannot revive a FAILED-but-lodged loan to APPROVED")
    void deleteRecordNeverRevives() {
        Loan loan = loan(LoanApprovalStatus.FAILED);
        loan.setBatchNumber("BATCH-20260901-07");

        service.processDeductionRequestResponse(BATCH, typed("ND-8002", NdasendaDeductionType.DELETE,
                NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.FAILED);
        assertThat(loan.getLoanAccountStatus()).isNull();
        assertThat(loan.getNextDisbursementAttemptDate()).isNull();
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(loanNotificationService);
        assertThat(auditedOnce().getDetail()).contains("reason=not_a_lodgement", "type=DELETE");
    }

    @Test
    @DisplayName("an ignored record already on the audit trail is not audited again on the next read")
    void ignoredRecordIsReportedOnce() {
        when(auditService.hasRecorded("NDASENDA_RESPONSE_IGNORED", "NDASENDA_DEDUCTION", "ND-8003")).thenReturn(true);

        service.processDeductionRequestResponse(BATCH, typed("ND-8003", NdasendaDeductionType.DELETE,
                NdasendaDeductionStatus.SUCCESS));

        verify(auditService, never()).record(any());
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("an EC number that is not the loan's: nothing applied, no SMS, audited MISMATCH with both masked")
    void ecMismatchIsNotApplied() {
        Loan loan = loan(LoanApprovalStatus.PROCESSING);
        loan.setEcNumber("7654321B");

        service.processDeductionRequestResponse(BATCH, typed("ND-8004", NdasendaDeductionType.NEW,
                NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(loan.getApprovalReference()).isNull();
        assertThat(loan.getLoanAccountStatus()).isNull();
        assertThat(loan.getNextDisbursementAttemptDate()).isNull();
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(loanNotificationService);

        AuditLog audit = auditedOnce();
        assertThat(audit.getEventType()).isEqualTo("NDASENDA_RESPONSE_MISMATCH");
        assertThat(audit.getEntityId()).isEqualTo("ND-8004");
        assertThat(audit.getCorrelationId()).isEqualTo(BATCH);
        assertThat(audit.getDetail())
                .contains("reason=ec_number_mismatch", "loanId=42", "loanStatus=PROCESSING",
                        "loanEcNumber=*****21B", "ecNumber=*****67A", "status=SUCCESS")
                .doesNotContain(EC_NUMBER, "7654321B", NATIONAL_ID);
    }

    @Test
    @DisplayName("a foreign SUCCESS on a declined loan is a MISMATCH, not a conflict: its deduction is not flagged")
    void ecMismatchCannotFlagAClosedLoan() {
        Loan loan = loan(LoanApprovalStatus.REJECTED);
        loan.setBatchNumber("BATCH-20260901-07");
        loan.setEcNumber("7654321B");

        service.processDeductionRequestResponse(BATCH, typed("ND-8005", NdasendaDeductionType.NEW,
                NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        verify(loanRepository, never()).save(any());
        assertThat(auditedOnce().getEventType()).isEqualTo("NDASENDA_RESPONSE_MISMATCH");
    }

    @Test
    @DisplayName("a mismatch already on the audit trail is not audited again while the loan keeps waiting")
    void mismatchIsReportedOnce() {
        Loan loan = loan(LoanApprovalStatus.PROCESSING);
        loan.setEcNumber("7654321B");
        when(auditService.hasRecorded("NDASENDA_RESPONSE_MISMATCH", "NDASENDA_DEDUCTION", "ND-8006")).thenReturn(true);

        service.processDeductionRequestResponse(BATCH, typed("ND-8006", NdasendaDeductionType.NEW,
                NdasendaDeductionStatus.SUCCESS));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        verify(auditService, never()).record(any());
    }

    @Test
    @DisplayName("a NEW record whose EC number matches up to case and spacing is applied as before")
    void matchingLodgementIsApplied() {
        Loan loan = loan(LoanApprovalStatus.PROCESSING);
        loan.setEcNumber(" 1234567a ");

        service.processDeductionRequestResponse(BATCH, typed("ND-8007", NdasendaDeductionType.NEW,
                NdasendaDeductionStatus.FAILED));

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-8007");
        assertThat(loan.getLoanStatusMessage()).isEqualTo("Insufficient net salary");
        verify(loanNotificationService).notify(loan, LoanNotice.DECLINED);
        verify(loanRepository).save(loan);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("only NEW or untyped records answer a lodgement; EC numbers are compared only when both are known")
    void matchingRules() {
        assertThat(NdasendaLoanApprovalServiceImpl.isLodgementAnswer(typed("a", NdasendaDeductionType.NEW, null))).isTrue();
        assertThat(NdasendaLoanApprovalServiceImpl.isLodgementAnswer(typed("b", null, null))).isTrue();
        assertThat(NdasendaLoanApprovalServiceImpl.isLodgementAnswer(typed("c", NdasendaDeductionType.CHANGE, null))).isFalse();
        assertThat(NdasendaLoanApprovalServiceImpl.isLodgementAnswer(typed("d", NdasendaDeductionType.DELETE, null))).isFalse();

        assertThat(NdasendaLoanApprovalServiceImpl.ecNumbersAgree("1234567A", " 1234567a")).isTrue();
        assertThat(NdasendaLoanApprovalServiceImpl.ecNumbersAgree("1234567-A", "1234567A")).isTrue();
        assertThat(NdasendaLoanApprovalServiceImpl.ecNumbersAgree("1234567A", "1234568A")).isFalse();
        assertThat(NdasendaLoanApprovalServiceImpl.ecNumbersAgree(null, "1234567A")).isTrue();
        assertThat(NdasendaLoanApprovalServiceImpl.ecNumbersAgree("1234567A", "  ")).isTrue();
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
