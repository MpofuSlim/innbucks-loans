package zw.co.reikan.loans.core.disbursements;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A loan that fails at the InnBucks step must say WHY. It used to read only
 * {@code disbursementStatus: FAILED}, leaving nobody able to tell bad data from
 * an outage from a refusal.
 */
class LoanAccountCreationJobTest {

    private DisbursementService disbursementService;
    private LoanRepository loanRepository;
    private LoanAccountCreationJob job;
    private Loan loan;

    @BeforeEach
    void setUp() {
        disbursementService = mock(DisbursementService.class);
        loanRepository = mock(LoanRepository.class);
        job = new LoanAccountCreationJob(disbursementService, loanRepository, mock(NotificationService.class));

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
}
