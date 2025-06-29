package zw.co.reikan.loans.core.disbursements;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoanDisbursementStatusResponseTest {

    @Test
    void isLoanFound_shouldReturnTrue_whenResponseCodeIsNot004() {
        // Arrange
        LoanDisbursementStatusResponse response = LoanDisbursementStatusResponse.builder()
                .responseCode("000")
                .build();

        // Act & Assert
        assertTrue(response.isLoanFound());
    }

    @Test
    void isLoanFound_shouldReturnFalse_whenResponseCodeIs004() {
        // Arrange
        LoanDisbursementStatusResponse response = LoanDisbursementStatusResponse.builder()
                .responseCode("004")
                .build();

        // Act & Assert
        assertFalse(response.isLoanFound());
    }

    @Test
    void isApproved_shouldReturnTrue_whenResponseCodeIs000() {
        // Arrange
        LoanDisbursementStatusResponse response = LoanDisbursementStatusResponse.builder()
                .responseCode("000")
                .build();

        // Act & Assert
        assertTrue(response.isApproved());
    }

    @Test
    void isApproved_shouldReturnFalse_whenResponseCodeIsNot000() {
        // Arrange
        LoanDisbursementStatusResponse response = LoanDisbursementStatusResponse.builder()
                .responseCode("001")
                .build();

        // Act & Assert
        assertFalse(response.isApproved());
    }

    @Test
    void determineLoanStatus_shouldReturnFailed_whenLoanIsNotFound() {
        // Arrange
        LoanDisbursementStatusResponse response = LoanDisbursementStatusResponse.builder()
                .responseCode("004")
                .build();

        // Act
        LoanDisbursementStatus status = response.determineLoanStatus();

        // Assert
        assertEquals(LoanDisbursementStatus.FAILED, status);
    }

    @Test
    void determineLoanStatus_shouldReturnSuccess_whenLoanIsApprovedAndStatusIsSettled() {
        // Arrange
        LoanDisbursementStatusResponse.LoanDetails loanDetails = new LoanDisbursementStatusResponse.LoanDetails();
        loanDetails.setStatus("SETTLED");

        LoanDisbursementStatusResponse.AdditionalData additionalData = new LoanDisbursementStatusResponse.AdditionalData();
        additionalData.setLoanDetails(loanDetails);

        LoanDisbursementStatusResponse response = LoanDisbursementStatusResponse.builder()
                .responseCode("000")
                .additionalData(additionalData)
                .build();

        // Act
        LoanDisbursementStatus status = response.determineLoanStatus();

        // Assert
        assertEquals(LoanDisbursementStatus.SUCCESS, status);
    }

    @Test
    void determineLoanStatus_shouldReturnPending_whenLoanIsApprovedButStatusIsNotSettled() {
        // Arrange
        LoanDisbursementStatusResponse.LoanDetails loanDetails = new LoanDisbursementStatusResponse.LoanDetails();
        loanDetails.setStatus("PROCESSING");

        LoanDisbursementStatusResponse.AdditionalData additionalData = new LoanDisbursementStatusResponse.AdditionalData();
        additionalData.setLoanDetails(loanDetails);

        LoanDisbursementStatusResponse response = LoanDisbursementStatusResponse.builder()
                .responseCode("000")
                .additionalData(additionalData)
                .build();

        // Act
        LoanDisbursementStatus status = response.determineLoanStatus();

        // Assert
        assertEquals(LoanDisbursementStatus.PENDING, status);
    }

    @Test
    void determineLoanStatus_shouldReturnPending_whenLoanIsApprovedButAdditionalDataIsNull() {
        // Arrange
        LoanDisbursementStatusResponse response = LoanDisbursementStatusResponse.builder()
                .responseCode("000")
                .additionalData(null)
                .build();

        // Act
        LoanDisbursementStatus status = response.determineLoanStatus();

        // Assert
        assertEquals(LoanDisbursementStatus.PENDING, status);
    }

    @Test
    void determineLoanStatus_shouldReturnPending_whenLoanIsApprovedButLoanDetailsIsNull() {
        // Arrange
        LoanDisbursementStatusResponse.AdditionalData additionalData = new LoanDisbursementStatusResponse.AdditionalData();
        additionalData.setLoanDetails(null);

        LoanDisbursementStatusResponse response = LoanDisbursementStatusResponse.builder()
                .responseCode("000")
                .additionalData(additionalData)
                .build();

        // Act
        LoanDisbursementStatus status = response.determineLoanStatus();

        // Assert
        assertEquals(LoanDisbursementStatus.PENDING, status);
    }
}