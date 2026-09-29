package zw.co.reikan.loans.core.disbursements;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.DeductionCancellationStatus;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanDisbursementRepository;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.merchant.Merchant;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A loan that fails at the InnBucks step must say WHY — and, because InnBucks books AND
 * pays on the one call, whether it DEFINITELY did not book: only a refusal may later be
 * recovered by a manual payout; anything ambiguous is held, never marked failed. A refusal
 * also flags the payroll deduction Ndasenda already accepted (BOOKING_FAILED); an ambiguous
 * outcome flags nothing, since the customer may hold the loan and the inquiry job settles it.
 */
class LoanAccountCreationJobTest {

    private DisbursementService disbursementService;
    private LoanRepository loanRepository;
    private AuditService auditService;
    private LoanDisbursementRepository loanDisbursementRepository;
    private PlatformTransactionManager transactionManager;
    private LoanAccountCreationJob job;
    private Loan loan;

    @BeforeEach
    void setUp() {
        disbursementService = mock(DisbursementService.class);
        loanRepository = mock(LoanRepository.class);
        auditService = mock(AuditService.class);
        loanDisbursementRepository = mock(LoanDisbursementRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        job = new LoanAccountCreationJob(disbursementService, loanRepository,
                new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class)),
                loanDisbursementRepository, auditService, transactionManager, 30);

        loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .loanAccountStatus(LoanAccountStatus.PENDING)
                .merchant(Merchant.builder().accountNumber("123456789").build())
                .batchNumber("BATCH-20260901-07")
                .ecNumber("1234567A")
                .build();
        loan.setId(42L);
        when(loanRepository.findIdsDueForBooking()).thenReturn(List.of(42L));
        when(loanRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));
    }

    private static HttpClientErrorException clientError(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private void assertHeldForInquiry() {
        assertThat(loan.getBookingFailureKind()).isEqualTo(BookingFailureKind.AMBIGUOUS);
        // CREATED + PENDING is what LoanDisbursementStatusJob polls: a booking that landed resolves.
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.CREATED);
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.PENDING);
        assertThat(loan.getDisbursementReference()).isEqualTo("000000042");
        // Once for the claim, once for the outcome.
        verify(loanRepository, times(2)).save(loan);
    }

    @Test
    @DisplayName("an InnBucks HTTP refusal records its status and body on the loan, as REFUSED")
    void httpRefusalRecordsInnbucksReason() {
        when(disbursementService.createLoanAccount(loan)).thenThrow(clientError(HttpStatus.BAD_REQUEST,
                "{\"responseCode\":\"05\",\"responseDescription\":\"Invalid idNumber\"}"));

        job.processLoanAccountCreation();

        assertThat(loan.getBookingFailureKind()).isEqualTo(BookingFailureKind.REFUSED);
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.FAILED);
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.FAILED);
        assertThat(loan.getDisbursementStatusMessage()).isEqualTo(
                "InnBucks loan application failed: HTTP 400 "
                        + "{\"responseCode\":\"05\",\"responseDescription\":\"Invalid idNumber\"}");
        verify(loanRepository, times(2)).save(loan);
    }

    @Test
    @DisplayName("a network failure is AMBIGUOUS: recorded with its message, held — not marked failed")
    void networkFailureRecordsMessage() {
        when(disbursementService.createLoanAccount(loan))
                .thenThrow(new ResourceAccessException("I/O error: Connection refused"));

        job.processLoanAccountCreation();

        assertHeldForInquiry();
        assertThat(loan.getDisbursementStatusMessage())
                .isEqualTo("InnBucks loan application outcome unknown (held, not failed): I/O error: Connection refused");
    }

    @Test
    @DisplayName("a read timeout is AMBIGUOUS — InnBucks may have booked and paid before we stopped listening")
    void readTimeoutIsAmbiguous() {
        when(disbursementService.createLoanAccount(loan))
                .thenThrow(new ResourceAccessException("I/O error: Read timed out"));

        job.processLoanAccountCreation();

        assertHeldForInquiry();
    }

    @Test
    @DisplayName("a 5xx is AMBIGUOUS, not a definite failure")
    void serverErrorIsAmbiguous() {
        when(disbursementService.createLoanAccount(loan)).thenThrow(HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        job.processLoanAccountCreation();

        assertHeldForInquiry();
        assertThat(loan.getDisbursementStatusMessage())
                .isEqualTo("InnBucks loan application outcome unknown (held, not failed): HTTP 502");
    }

    @Test
    @DisplayName("an unreadable answer is AMBIGUOUS")
    void parseErrorIsAmbiguous() {
        when(disbursementService.createLoanAccount(loan))
                .thenThrow(new RestClientException("Error while extracting response"));

        job.processLoanAccountCreation();

        assertHeldForInquiry();
    }

    @Test
    @DisplayName("a 409 is AMBIGUOUS — on a booking keyed by participantReference it may mean 'already booked'")
    void conflictIsAmbiguous() {
        when(disbursementService.createLoanAccount(loan))
                .thenThrow(clientError(HttpStatus.CONFLICT, "{\"responseDescription\":\"Duplicate reference\"}"));

        job.processLoanAccountCreation();

        assertHeldForInquiry();
    }

    @Test
    @DisplayName("a 401 surviving the one re-authenticated replay is InnBucks refusing us: REFUSED")
    void finalUnauthorizedIsRefused() {
        when(disbursementService.createLoanAccount(loan)).thenThrow(clientError(HttpStatus.UNAUTHORIZED, ""));

        job.processLoanAccountCreation();

        assertThat(loan.getBookingFailureKind()).isEqualTo(BookingFailureKind.REFUSED);
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.FAILED);
    }

    @Test
    @DisplayName("an unsuccessful response we read records its message, as REFUSED")
    void unsuccessfulResponseRecordsMessage() {
        when(disbursementService.createLoanAccount(loan)).thenReturn(LoanAccountCreationResponse.builder()
                .reference("000000042").success(false).message("InnBucks answered HTTP 302").build());

        job.processLoanAccountCreation();

        assertThat(loan.getBookingFailureKind()).isEqualTo(BookingFailureKind.REFUSED);
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.FAILED);
        assertThat(loan.getDisbursementStatusMessage())
                .isEqualTo("InnBucks loan application failed: InnBucks answered HTTP 302");
    }

    @Test
    @DisplayName("a long InnBucks body is cut to fit the VARCHAR(255) column")
    void longReasonIsTruncatedToTheColumn() {
        when(disbursementService.createLoanAccount(loan))
                .thenThrow(new ResourceAccessException("x".repeat(400)));

        job.processLoanAccountCreation();

        assertThat(loan.getDisbursementStatusMessage()).hasSize(255).endsWith("...");
    }

    @Test
    @DisplayName("success leaves no failure message, clears an earlier refusal and marks the account CREATED")
    void successLeavesNoMessage() {
        loan.setBookingFailureKind(BookingFailureKind.REFUSED);
        when(disbursementService.createLoanAccount(loan)).thenReturn(LoanAccountCreationResponse.builder()
                .reference("000000042").success(true).build());

        job.processLoanAccountCreation();

        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.CREATED);
        assertThat(loan.getBookingFailureKind()).isNull();
        assertThat(loan.getDisbursementStatusMessage()).isNull();
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8);
    }

    private AuditLog requiredAuditedOnce() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(captor.capture());
        return captor.getValue().build();
    }

    @Test
    @DisplayName("an InnBucks 4xx refusal flags the lodged deduction BOOKING_FAILED, saved with the failure")
    void refusalFlagsDeductionBookingFailed() {
        when(disbursementService.createLoanAccount(loan)).thenThrow(clientError(HttpStatus.BAD_REQUEST));

        job.processLoanAccountCreation();

        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("BOOKING_FAILED");
        verify(loanRepository, times(2)).save(loan);
        AuditLog audit = requiredAuditedOnce();
        assertThat(audit.getEventType()).isEqualTo("DEDUCTION_CANCELLATION_REQUIRED");
        assertThat(audit.getActorId()).isEqualTo("loan-account-creation-job");
        assertThat(audit.getDetail()).contains("reason=BOOKING_FAILED").doesNotContain("1234567A");
    }

    @Test
    @DisplayName("a non-2xx answer InnBucks gave without throwing is a refusal too")
    void unsuccessfulResponseFlagsDeductionBookingFailed() {
        when(disbursementService.createLoanAccount(loan)).thenReturn(LoanAccountCreationResponse.builder()
                .reference("000000042").success(false).message("InnBucks answered HTTP 302").build());

        job.processLoanAccountCreation();

        assertThat(loan.getDeductionCancellationReason()).isEqualTo("BOOKING_FAILED");
    }

    @Test
    @DisplayName("a timed-out booking is held for the inquiry job and flags nothing — the customer may hold the loan")
    void timeoutIsHeldAndFlagsNothing() {
        when(disbursementService.createLoanAccount(loan)).thenThrow(new ResourceAccessException(
                "I/O error on POST request: Read timed out", new SocketTimeoutException("Read timed out")));

        job.processLoanAccountCreation();

        assertHeldForInquiry();
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("a 5xx, a 409 or a 408 may follow a booking that landed: AMBIGUOUS, nothing flagged")
    void serverErrorsAndAmbiguousClientErrorsFlagNothing() {
        for (Exception ex : List.of(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8),
                clientError(HttpStatus.CONFLICT),
                clientError(HttpStatus.REQUEST_TIMEOUT))) {
            assertThat(LoanAccountCreationJob.classify(ex)).as(ex.getMessage()).isEqualTo(BookingFailureKind.AMBIGUOUS);
        }
        assertThat(LoanAccountCreationJob.classify(clientError(HttpStatus.UNAUTHORIZED)))
                .isEqualTo(BookingFailureKind.REFUSED);
        assertThat(LoanAccountCreationJob.classify(clientError(HttpStatus.UNPROCESSABLE_ENTITY)))
                .isEqualTo(BookingFailureKind.REFUSED);

        when(disbursementService.createLoanAccount(loan)).thenThrow(clientError(HttpStatus.CONFLICT));
        job.processLoanAccountCreation();
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("an old loan is flagged when its booking fails, like any other")
    void oldLoanIsFlaggedToo() {
        loan.setCreatedDate(LocalDateTime.now().minusDays(90));
        when(disbursementService.createLoanAccount(loan)).thenThrow(clientError(HttpStatus.BAD_REQUEST));

        job.processLoanAccountCreation();

        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
    }

    @Test
    @DisplayName("a successful booking flags nothing")
    void successFlagsNothing() {
        when(disbursementService.createLoanAccount(loan)).thenReturn(LoanAccountCreationResponse.builder()
                .reference("000000042").success(true).build());

        job.processLoanAccountCreation();

        assertThat(loan.getDeductionCancellationStatus()).isNull();
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("a loan already disbursed is never booked — booking would pay it again")
    void skipsALoanAlreadyDisbursed() {
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);

        job.processLoanAccountCreation();

        verify(disbursementService, never()).createLoanAccount(any());
        verify(loanRepository, never()).save(any());
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.SUCCESS);
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.PENDING);
    }

    @Test
    @DisplayName("a loan with any manual payout attempt is never booked")
    void skipsALoanWithAManualPayoutAttempt() {
        when(loanDisbursementRepository.existsByLoanId(42L)).thenReturn(true);

        job.processLoanAccountCreation();

        verify(disbursementService, never()).createLoanAccount(any());
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("a loan whose earlier booking is AMBIGUOUS is never re-booked, even if reset to PENDING")
    void skipsALoanWithAnAmbiguousBooking() {
        loan.setBookingFailureKind(BookingFailureKind.AMBIGUOUS);

        job.processLoanAccountCreation();

        verify(disbursementService, never()).createLoanAccount(any());
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("NOT SENT (login down, connection refused): the loan stays PENDING untouched, and the run stops")
    void notSentLeavesTheLoanPendingAndStopsTheRun() {
        Loan second = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .loanAccountStatus(LoanAccountStatus.PENDING)
                .merchant(Merchant.builder().accountNumber("123456789").build())
                .build();
        second.setId(43L);
        when(loanRepository.findIdsDueForBooking()).thenReturn(List.of(42L, 43L));
        when(loanRepository.findByIdForUpdate(43L)).thenReturn(Optional.of(second));
        when(disbursementService.createLoanAccount(loan)).thenThrow(new BookingNotSentException(
                "Booking of loan 000000042 not sent: InnBucks login failed (HTTP 503)", new RuntimeException()));

        job.processLoanAccountCreation();

        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.PENDING);
        assertThat(loan.getDisbursementStatus()).isNull();
        assertThat(loan.getBookingFailureKind()).isNull();
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        assertThat(loan.getDisbursementStatusMessage()).contains("InnBucks login failed").endsWith("will retry");
        // The claim is released, so a later run books it.
        assertThat(loan.getBookingClaimedAt()).isNull();
        verify(loanRepository, times(2)).save(loan);
        verify(disbursementService, never()).createLoanAccount(second);
        verifyNoInteractions(auditService);
    }

    // ── The claim: committed before InnBucks is called, so a booking is never sent twice ──────────

    private Loan secondLoan() {
        Loan second = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .loanAccountStatus(LoanAccountStatus.PENDING)
                .merchant(Merchant.builder().accountNumber("123456789").build())
                .build();
        second.setId(43L);
        when(loanRepository.findIdsDueForBooking()).thenReturn(List.of(42L, 43L));
        when(loanRepository.findByIdForUpdate(43L)).thenReturn(Optional.of(second));
        return second;
    }

    private static LoanAccountCreationResponse booked() {
        return LoanAccountCreationResponse.builder().reference("000000042").success(true).build();
    }

    @Test
    @DisplayName("the claim commits before InnBucks is called; the outcome settles in a transaction of its own")
    void claimCommitsBeforeTheCall() {
        LocalDateTime[] claimAtCall = new LocalDateTime[1];
        when(disbursementService.createLoanAccount(loan)).thenAnswer(call -> {
            claimAtCall[0] = loan.getBookingClaimedAt();
            return booked();
        });

        job.processLoanAccountCreation();

        assertThat(claimAtCall[0]).isNotNull();
        // Kept after a booking that landed, as the record of when it was sent.
        assertThat(loan.getBookingClaimedAt()).isEqualTo(claimAtCall[0]);
        InOrder order = inOrder(loanRepository, transactionManager, disbursementService);
        order.verify(loanRepository).save(loan);
        order.verify(transactionManager).commit(any());
        order.verify(disbursementService).createLoanAccount(loan);
        order.verify(loanRepository).save(loan);
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("a loan another run or instance has claimed is not booked again")
    void claimedLoanIsNotBookedAgain() {
        loan.setBookingClaimedAt(LocalDateTime.now().minusMinutes(1));

        job.processLoanAccountCreation();

        verify(disbursementService, never()).createLoanAccount(any());
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("an outcome for a loan that moved on during the call is recorded, not applied")
    void supersededOutcomeIsNotApplied() {
        when(disbursementService.createLoanAccount(loan)).thenAnswer(call -> {
            // Settled elsewhere while InnBucks was answering.
            loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
            loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
            throw clientError(HttpStatus.BAD_REQUEST);
        });

        job.processLoanAccountCreation();

        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.SUCCESS);
        assertThat(loan.getBookingFailureKind()).isNull();
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        AuditLog audit = requiredAuditedOnce();
        assertThat(audit.getEventType()).isEqualTo("INNBUCKS_BOOKING_NOT_RECORDED");
        assertThat(audit.getDetail()).contains("reason=claim_superseded", "outcome=REFUSED");
    }

    @Test
    @DisplayName("an outcome that cannot be saved leaves the claim standing, so the loan is never booked again")
    void unsavedOutcomeKeepsTheClaim() {
        when(disbursementService.createLoanAccount(loan)).thenReturn(booked());
        when(loanRepository.save(loan)).thenAnswer(call -> call.getArgument(0))
                .thenThrow(new IllegalStateException("database unavailable"));

        job.processLoanAccountCreation();

        assertThat(loan.getBookingClaimedAt()).isNotNull();
        AuditLog audit = requiredAuditedOnce();
        assertThat(audit.getEventType()).isEqualTo("INNBUCKS_BOOKING_NOT_RECORDED");
        assertThat(audit.getDetail()).contains("reason=settle_failed", "outcome=BOOKED");
    }

    @Test
    @DisplayName("a claim left unsettled past the window is held for the inquiry job, never booked again")
    void staleClaimIsHeldForTheInquiryJob() {
        LocalDateTime claimedAt = LocalDateTime.now().minusMinutes(45);
        loan.setBookingClaimedAt(claimedAt);
        when(loanRepository.findIdsWithBookingClaimedBefore(any())).thenReturn(List.of(42L));

        job.processLoanAccountCreation();

        assertThat(loan.getBookingFailureKind()).isEqualTo(BookingFailureKind.AMBIGUOUS);
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.CREATED);
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.PENDING);
        assertThat(loan.getDisbursementStatusMessage()).contains("was never recorded");
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        verify(disbursementService, never()).createLoanAccount(any());
        AuditLog audit = requiredAuditedOnce();
        assertThat(audit.getEventType()).isEqualTo("INNBUCKS_BOOKING_CLAIM_ABANDONED");
        assertThat(audit.getDetail()).contains("claimedAt=" + claimedAt);
    }

    @Test
    @DisplayName("a claim still inside the window is left alone, whatever the query returned")
    void recentClaimIsNotHeld() {
        loan.setBookingClaimedAt(LocalDateTime.now().minusMinutes(5));
        when(loanRepository.findIdsWithBookingClaimedBefore(any())).thenReturn(List.of(42L));

        job.processLoanAccountCreation();

        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.PENDING);
        assertThat(loan.getBookingFailureKind()).isNull();
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unknown outcome stops the run: the loans behind it are not sent into the same failure")
    void unknownOutcomeStopsTheRun() {
        Loan second = secondLoan();
        when(disbursementService.createLoanAccount(loan))
                .thenThrow(new ResourceAccessException("I/O error: Read timed out"));

        job.processLoanAccountCreation();

        assertHeldForInquiry();
        verify(disbursementService, never()).createLoanAccount(second);
        assertThat(second.getBookingClaimedAt()).isNull();
    }

    @Test
    @DisplayName("a refusal is about that loan alone: the run carries on to the next")
    void refusalCarriesOn() {
        Loan second = secondLoan();
        when(disbursementService.createLoanAccount(loan)).thenThrow(clientError(HttpStatus.BAD_REQUEST));
        when(disbursementService.createLoanAccount(second)).thenReturn(booked());

        job.processLoanAccountCreation();

        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.FAILED);
        assertThat(second.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.CREATED);
    }

    @Test
    @DisplayName("an answer arriving after the sweep held the loan still settles it: the answer is better evidence")
    void lateAnswerSettlesAHeldLoan() {
        when(disbursementService.createLoanAccount(loan)).thenAnswer(call -> {
            // The stale-claim sweep holds it while the call is still running; the claim is unchanged.
            loan.setBookingFailureKind(BookingFailureKind.AMBIGUOUS);
            loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
            loan.setDisbursementStatus(LoanDisbursementStatus.PENDING);
            return booked();
        });

        job.processLoanAccountCreation();

        assertThat(loan.getBookingFailureKind()).isNull();
        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.CREATED);
        verifyNoInteractions(auditService);
    }
}
