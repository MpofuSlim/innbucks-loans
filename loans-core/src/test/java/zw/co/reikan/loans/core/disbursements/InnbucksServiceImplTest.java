package zw.co.reikan.loans.core.disbursements;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanDisbursementRepository;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InnbucksServiceImplTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private InnbucksParameters parameters;

    @Mock
    private InnbucksAuthService innbucksAuthService;

    @Mock
    private LoanRepository loanRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private LoanDisbursementRepository loanDisbursementRepository;

    @Mock
    private Loan testLoan;

    @InjectMocks
    private InnbucksServiceImpl innbucksService;

    private String testToken;
    private String testApiKey;
    private String testInquiryEndpoint;
    private LoanDisbursementStatusResponse successResponse;

    @BeforeEach
    void setUp() {
        // Setup test data
        testToken = "test-token";
        testApiKey = "test-api-key";
        testInquiryEndpoint = "https://test.com/api/loan/inquiry/{participantReference}";

        // Setup mock responses
        when(testLoan.getId()).thenReturn(1L);
        when(testLoan.getReference()).thenReturn("000000001");
        when(innbucksAuthService.getAccessToken()).thenReturn(testToken);
        when(parameters.getApiKey()).thenReturn(testApiKey);
        when(parameters.getLoanInquiryEndpoint()).thenReturn(testInquiryEndpoint);

        // Setup success response
        LoanDisbursementStatusResponse.LoanDetails loanDetails = new LoanDisbursementStatusResponse.LoanDetails();
        loanDetails.setStatus("SETTLED");

        LoanDisbursementStatusResponse.AdditionalData additionalData = new LoanDisbursementStatusResponse.AdditionalData();
        additionalData.setLoanDetails(loanDetails);

        successResponse = LoanDisbursementStatusResponse.builder()
                .responseCode("000")
                .responseDescription("Approved or completed successfully")
                .reference("REF123")
                .participantReference("000000156")
                .additionalData(additionalData)
                .build();
    }

    @Test
    void checkLoanDisbursementStatus_shouldReturnSuccessResponse_whenApiCallSucceeds() {
        // Arrange
        ResponseEntity<LoanDisbursementStatusResponse> responseEntity = new ResponseEntity<>(successResponse, HttpStatus.OK);
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(LoanDisbursementStatusResponse.class)
        )).thenReturn(responseEntity);

        // Act
        LoanDisbursementStatusResponse result = innbucksService.checkLoanDisbursementStatus(testLoan);

        // Assert
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(LoanDisbursementStatus.SUCCESS, result.getStatus());

        // Verify correct URL and headers were used
        verify(parameters).getLoanInquiryEndpoint();
        verify(innbucksAuthService).getAccessToken();
        verify(parameters).getApiKey();
    }

    @Test
    void checkLoanDisbursementStatus_shouldRefreshTokenAndRetry_whenUnauthorized() {
        // Arrange
        HttpClientErrorException unauthorizedException = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);

        // First call throws unauthorized, second call succeeds
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(LoanDisbursementStatusResponse.class)
        )).thenThrow(unauthorizedException).thenReturn(new ResponseEntity<>(successResponse, HttpStatus.OK));

        // Act
        LoanDisbursementStatusResponse result = innbucksService.checkLoanDisbursementStatus(testLoan);

        // Assert
        assertNotNull(result);
        assertTrue(result.isSuccess());

        // Verify token was refreshed
        verify(innbucksAuthService).refreshToken();

        // Verify exchange was called twice (first throws, second succeeds)
        verify(restTemplate, times(2)).exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(LoanDisbursementStatusResponse.class)
        );
    }

    @Test
    void checkLoanDisbursementStatus_shouldReturnErrorResponse_whenApiCallFails() {
        // Arrange
        RuntimeException testException = new RuntimeException("Test exception");
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(LoanDisbursementStatusResponse.class)
        )).thenThrow(testException);

        // Act
        LoanDisbursementStatusResponse result = innbucksService.checkLoanDisbursementStatus(testLoan);

        // Assert
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("999", result.getResponseCode());
        assertTrue(result.getResponseDescription().contains("Test exception"));
        assertEquals(LoanDisbursementStatus.PENDING, result.getStatus());
    }

    @Test
    void checkLoanDisbursementStatus_shouldHandleNullResponse() {
        // Arrange
        ResponseEntity<LoanDisbursementStatusResponse> responseEntity = new ResponseEntity<>(null, HttpStatus.OK);
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(LoanDisbursementStatusResponse.class)
        )).thenReturn(responseEntity);
        when(testLoan.getReference()).thenReturn("000000001");

        // Act
        LoanDisbursementStatusResponse result = innbucksService.checkLoanDisbursementStatus(testLoan);

        // Assert
        assertNotNull(result);
        assertEquals("999", result.getResponseCode());
        assertTrue(result.getResponseDescription().contains("Null response received from Innbucks API"));
        assertEquals(LoanDisbursementStatus.PENDING, result.getStatus());
        assertFalse(result.isSuccess());
    }

    @Test
    void checkLoanDisbursementStatus_shouldSetCorrectStatus_whenResponseIsNotApproved() {
        // Arrange
        LoanDisbursementStatusResponse notApprovedResponse = LoanDisbursementStatusResponse.builder()
                .responseCode("001")
                .responseDescription("Not approved")
                .build();

        ResponseEntity<LoanDisbursementStatusResponse> responseEntity = 
                new ResponseEntity<>(notApprovedResponse, HttpStatus.OK);

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(LoanDisbursementStatusResponse.class)
        )).thenReturn(responseEntity);

        // Act
        LoanDisbursementStatusResponse result = innbucksService.checkLoanDisbursementStatus(testLoan);

        // Assert
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(LoanDisbursementStatus.PENDING, result.getStatus());
    }
}
