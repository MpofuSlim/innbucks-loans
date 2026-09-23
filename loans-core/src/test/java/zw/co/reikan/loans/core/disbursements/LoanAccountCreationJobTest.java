package zw.co.reikan.loans.core.disbursements;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.loan.DeductionCancellationDto;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.DeductionCancellationStatus;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A loan that fails at the InnBucks step must say WHY. It used to read only
 * {@code disbursementStatus: FAILED}, leaving nobody able to tell bad data from
 * an outage from a refusal. The failure also flags the payroll deduction Ndasenda
 * already accepted — as BOOKING_FAILED only when InnBucks itself refused, since a
 * timed-out booking may have booked and paid the loan.
 */
class LoanAccountCreationJobTest {

    private DisbursementService disbursementService;
    private LoanRepository loanRepository;
    private AuditService auditService;
    private LoanAccountCreationJob job;
    private Loan loan;

    @BeforeEach
    void setUp() {
        disbursementService = mock(DisbursementService.class);
        loanRepository = mock(LoanRepository.class);
        auditService = mock(AuditService.class);
        job = new LoanAccountCreationJob(disbursementService, loanRepository, mock(NotificationService.class),
                new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class)));

        loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .loanAccountStatus(LoanAccountStatus.PENDING)
                .merchant(Merchant.builder().accountNumber("123456789").build())
                .batchNumber("BATCH-20260901-07")
                .ecNumber("1234567A")
                .build();
        loan.setId(42L);
        when(loanRepository.findByLoanApprovalStatusAndInternalApprovalStatusAndLoanAccountStatus(
                any(), any(), any())).thenReturn(List.of(loan));
    }

    @Test
    @DisplayName("an InnBucks HTTP refusal records its status and body on the loan")
    void httpRefusalRecordsInnbucksReason() {
        when(disbursementService.createLoanAccount(loan)).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"responseCode\":\"05\",\"responseDescription\":\"Invalid idNumber\"}"
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        job.processLoanAccountCreation();

        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.FAILED);
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.FAILED);
        assertThat(loan.getDisbursementStatusMessage()).isEqualTo(
                "InnBucks loan application failed: HTTP 400 "
                        + "{\"responseCode\":\"05\",\"responseDescription\":\"Invalid idNumber\"}");
        verify(loanRepository).save(loan);
    }

    @Test
    @DisplayName("a network failure records the exception message")
    void networkFailureRecordsMessage() {
        when(disbursementService.createLoanAccount(loan))
                .thenThrow(new ResourceAccessException("I/O error: Connection refused"));

        job.processLoanAccountCreation();

        assertThat(loan.getDisbursementStatusMessage())
                .isEqualTo("InnBucks loan application failed: I/O error: Connection refused");
    }

    @Test
    @DisplayName("an unsuccessful response records its message")
    void unsuccessfulResponseRecordsMessage() {
        when(disbursementService.createLoanAccount(loan)).thenReturn(LoanAccountCreationResponse.builder()
                .reference("000000042").success(false).message("InnBucks answered HTTP 302").build());

        job.processLoanAccountCreation();

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
    @DisplayName("success leaves no failure message and marks the account CREATED")
    void successLeavesNoMessage() {
        when(disbursementService.createLoanAccount(loan)).thenReturn(LoanAccountCreationResponse.builder()
                .reference("000000042").success(true).build());

        job.processLoanAccountCreation();

        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.CREATED);
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
        verify(loanRepository).save(loan);
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
    @DisplayName("a timed-out booking is flagged IN DOUBT, never plain BOOKING_FAILED, and the queue says to check first")
    void timeoutIsFlaggedInDoubtNotBookingFailed() {
        when(disbursementService.createLoanAccount(loan)).thenThrow(new ResourceAccessException(
                "I/O error on POST request: Read timed out", new SocketTimeoutException("Read timed out")));

        job.processLoanAccountCreation();

        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("BOOKING_IN_DOUBT");
        assertThat(requiredAuditedOnce().getDetail()).contains("reason=BOOKING_IN_DOUBT");

        DeductionCancellationDto row = DeductionCancellationDto.from(loan);
        assertThat(row.getReason()).isEqualTo("BOOKING_IN_DOUBT");
        assertThat(row.getAction()).contains("confirm with InnBucks that no loan was booked");
        assertThat(row.getDisbursementStatusMessage()).contains("Read timed out");
    }

    @Test
    @DisplayName("a 5xx, a 409 or a 408 may follow a booking that landed: IN DOUBT")
    void serverErrorsAndAmbiguousClientErrorsAreInDoubt() {
        for (Exception ex : List.of(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8),
                clientError(HttpStatus.CONFLICT),
                clientError(HttpStatus.REQUEST_TIMEOUT))) {
            assertThat(LoanAccountCreationJob.refusedByInnbucks(ex)).as(ex.getMessage()).isFalse();
        }
        assertThat(LoanAccountCreationJob.refusedByInnbucks(clientError(HttpStatus.UNAUTHORIZED))).isTrue();
        assertThat(LoanAccountCreationJob.refusedByInnbucks(clientError(HttpStatus.UNPROCESSABLE_ENTITY))).isTrue();

        when(disbursementService.createLoanAccount(loan)).thenThrow(clientError(HttpStatus.CONFLICT));
        job.processLoanAccountCreation();
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("BOOKING_IN_DOUBT");
    }

    @Test
    @DisplayName("a loan older than the saga's 30-day window is still flagged when its booking fails")
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
}
