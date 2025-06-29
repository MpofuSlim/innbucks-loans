package zw.co.reikan.loans.core.disbursements;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.spy;

@ExtendWith(MockitoExtension.class)
class LoanDisbursementStatusJobTest {

    @Mock
    private DisbursementService disbursementService;

    @Mock
    private LoanRepository loanRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private LoanDisbursementStatusJob loanDisbursementStatusJob;

    private Loan testLoan;
    private LoanDisbursementStatusResponse successResponse;
    private LoanDisbursementStatusResponse pendingResponse;
    private LoanDisbursementStatusResponse failedResponse;

    @BeforeEach
    void setUp() {
        // Setup test loan as a spy
        testLoan = spy(new Loan());
        testLoan.setId(1L);
        testLoan.setDisbursedAmount(BigDecimal.valueOf(100.00));
        testLoan.setMobileNumber("1234567890");
        testLoan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        testLoan.setDisbursementStatus(LoanDisbursementStatus.PENDING);

        // Setup success response
        LoanDisbursementStatusResponse.LoanDetails successLoanDetails = new LoanDisbursementStatusResponse.LoanDetails();
        successLoanDetails.setStatus("SETTLED");

        LoanDisbursementStatusResponse.AdditionalData successAdditionalData = new LoanDisbursementStatusResponse.AdditionalData();
        successAdditionalData.setLoanDetails(successLoanDetails);

        successResponse = LoanDisbursementStatusResponse.builder()
                .responseCode("000")
                .responseDescription("Approved or completed successfully")
                .reference("REF123")
                .participantReference("000000156")
                .additionalData(successAdditionalData)
                .success(true)
                .status(LoanDisbursementStatus.SUCCESS)
                .build();

        // Setup pending response
        LoanDisbursementStatusResponse.LoanDetails pendingLoanDetails = new LoanDisbursementStatusResponse.LoanDetails();
        pendingLoanDetails.setStatus("PROCESSING");

        LoanDisbursementStatusResponse.AdditionalData pendingAdditionalData = new LoanDisbursementStatusResponse.AdditionalData();
        pendingAdditionalData.setLoanDetails(pendingLoanDetails);

        pendingResponse = LoanDisbursementStatusResponse.builder()
                .responseCode("000")
                .responseDescription("Approved or completed successfully")
                .reference("REF123")
                .participantReference("000000156")
                .additionalData(pendingAdditionalData)
                .success(true)
                .status(LoanDisbursementStatus.PENDING)
                .build();

        // Setup failed response
        failedResponse = LoanDisbursementStatusResponse.builder()
                .responseCode("004")
                .responseDescription("Loan application not found")
                .reference("REF123")
                .participantReference("000000156")
                .success(true)
                .status(LoanDisbursementStatus.FAILED)
                .build();
    }

    @Test
    void processLoanDisbursementStatus_shouldProcessLoans_whenLoansExist() {
        // Arrange
        when(loanRepository.findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING))
                .thenReturn(List.of(testLoan));
        when(disbursementService.checkLoanDisbursementStatus(testLoan)).thenReturn(successResponse);

        // Act
        loanDisbursementStatusJob.processLoanDisbursementStatus();

        // Assert
        verify(loanRepository).findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING);
        verify(disbursementService).checkLoanDisbursementStatus(testLoan);
        verify(loanRepository).save(testLoan);
        verify(notificationService).sendSms(anyString(), anyString());

        // Verify loan was updated correctly
        verify(testLoan).setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        verify(testLoan).setDateDisbursed(any(LocalDateTime.class));
    }

    @Test
    void processLoanDisbursementStatus_shouldDoNothing_whenNoLoansExist() {
        // Arrange
        when(loanRepository.findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING))
                .thenReturn(Collections.emptyList());

        // Act
        loanDisbursementStatusJob.processLoanDisbursementStatus();

        // Assert
        verify(loanRepository).findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING);
        verify(disbursementService, never()).checkLoanDisbursementStatus(any());
        verify(loanRepository, never()).save(any());
    }

    @Test
    void checkLoanDisbursementStatus_shouldUpdateLoanToSuccess_whenResponseIsSuccess() {
        // Arrange
        when(loanRepository.findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING))
                .thenReturn(List.of(testLoan));
        when(disbursementService.checkLoanDisbursementStatus(testLoan)).thenReturn(successResponse);

        // Reset the spy to clear the initial setup calls
        reset(testLoan);
        testLoan.setId(1L);
        testLoan.setDisbursedAmount(BigDecimal.valueOf(100.00));
        testLoan.setMobileNumber("1234567890");
        testLoan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        testLoan.setDisbursementStatus(LoanDisbursementStatus.PENDING);

        // Act
        loanDisbursementStatusJob.processLoanDisbursementStatus();

        // Assert
        verify(testLoan).setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        verify(testLoan).setDateDisbursed(any(LocalDateTime.class));
        verify(notificationService).sendSms(anyString(), anyString());
        verify(loanRepository).save(testLoan);
    }

    @Test
    void checkLoanDisbursementStatus_shouldKeepLoanAsPending_whenResponseIsPending() {
        // Arrange
        when(loanRepository.findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING))
                .thenReturn(List.of(testLoan));
        when(disbursementService.checkLoanDisbursementStatus(testLoan)).thenReturn(pendingResponse);

        // Reset the spy to clear the initial setup calls
        reset(testLoan);
        testLoan.setId(1L);
        testLoan.setDisbursedAmount(BigDecimal.valueOf(100.00));
        testLoan.setMobileNumber("1234567890");
        testLoan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        testLoan.setDisbursementStatus(LoanDisbursementStatus.PENDING);

        // Act
        loanDisbursementStatusJob.processLoanDisbursementStatus();

        // Assert
        // Since we're setting PENDING in setup and the method sets it again, we verify it was called at least once
        verify(testLoan, atLeastOnce()).setDisbursementStatus(LoanDisbursementStatus.PENDING);
        verify(testLoan, never()).setDateDisbursed(any(LocalDateTime.class));
        verify(notificationService, never()).sendSms(anyString(), anyString());
        verify(loanRepository).save(testLoan);
    }

    @Test
    void checkLoanDisbursementStatus_shouldUpdateLoanToFailed_whenResponseIsFailed() {
        // Arrange
        when(loanRepository.findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING))
                .thenReturn(List.of(testLoan));
        when(disbursementService.checkLoanDisbursementStatus(testLoan)).thenReturn(failedResponse);

        // Act
        loanDisbursementStatusJob.processLoanDisbursementStatus();

        // Assert
        verify(testLoan).setDisbursementStatus(LoanDisbursementStatus.FAILED);
        verify(testLoan).setDisbursementStatusMessage(anyString());
        verify(testLoan, never()).setDateDisbursed(any(LocalDateTime.class));
        verify(notificationService, never()).sendSms(anyString(), anyString());
        verify(loanRepository).save(testLoan);
    }

    @Test
    void checkLoanDisbursementStatus_shouldHandleException_whenServiceThrowsException() {
        // Arrange
        when(loanRepository.findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING))
                .thenReturn(List.of(testLoan));
        when(disbursementService.checkLoanDisbursementStatus(testLoan)).thenThrow(new RuntimeException("Test exception"));

        // Act
        loanDisbursementStatusJob.processLoanDisbursementStatus();

        // Assert
        verify(testLoan).setDisbursementStatusMessage(contains("Status check exception"));
        verify(loanRepository).save(testLoan);
    }

    @Test
    void notifyCustomer_shouldHandleException_whenNotificationServiceThrowsException() {
        // Arrange
        when(loanRepository.findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING))
                .thenReturn(List.of(testLoan));
        when(disbursementService.checkLoanDisbursementStatus(testLoan)).thenReturn(successResponse);
        doThrow(new RuntimeException("Notification failed")).when(notificationService).sendSms(anyString(), anyString());

        // Act
        loanDisbursementStatusJob.processLoanDisbursementStatus();

        // Assert
        // Verify that the loan is still marked as SUCCESS even if notification fails
        verify(testLoan).setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        verify(testLoan).setDateDisbursed(any(LocalDateTime.class));
        verify(loanRepository).save(testLoan);
    }
}
