package zw.co.reikan.loans.core.disbursements;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanDisbursementRepository;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A loan that fails at the InnBucks step must say WHY — and, because InnBucks books AND
 * pays on the one call, whether it DEFINITELY did not book: only a refusal may later be
 * recovered by a manual payout; anything ambiguous is held, never marked failed.
 */
class LoanAccountCreationJobTest {

    private DisbursementService disbursementService;
    private LoanRepository loanRepository;
    private LoanDisbursementRepository loanDisbursementRepository;
    private LoanAccountCreationJob job;
    private Loan loan;

    @BeforeEach
    void setUp() {
        disbursementService = mock(DisbursementService.class);
        loanRepository = mock(LoanRepository.class);
        loanDisbursementRepository = mock(LoanDisbursementRepository.class);
        job = new LoanAccountCreationJob(disbursementService, loanRepository, mock(NotificationService.class),
                loanDisbursementRepository);

        loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .loanAccountStatus(LoanAccountStatus.PENDING)
                .merchant(Merchant.builder().accountNumber("123456789").build())
                .build();
        loan.setId(42L);
        when(loanRepository.findByLoanApprovalStatusAndInternalApprovalStatusAndLoanAccountStatus(
                any(), any(), any())).thenReturn(List.of(loan));
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
        verify(loanRepository).save(loan);
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
        verify(loanRepository).save(loan);
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
}
