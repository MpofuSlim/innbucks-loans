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
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanBatchService;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * An SSB (payroll) rejection used to paste Ndasenda's raw response message into
 * the customer's decline SMS. Upstream text is for staff: the customer gets the
 * fixed decline, and the reason is kept on the loan for whoever they contact.
 */
class NdasendaLoanApprovalServiceImplTest {

    private static final String BY_DATE = "responses-by-date";
    private static final String BY_BATCH = "responses-by-batch";
    private static final String NDASENDA_REASON = "Deduction exceeds 40% of net salary; ref: EC/1234";

    private RestTemplate restTemplate;
    private LoanRepository loanRepository;
    private NotificationService notificationService;
    private NdasendaLoanApprovalServiceImpl service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        loanRepository = mock(LoanRepository.class);
        notificationService = mock(NotificationService.class);
        NdasendaParameters props = new NdasendaParameters();
        props.setDeductionResponsesByDateRangeEndpoint(BY_DATE);
        props.setDeductionResponsesByBatchId(BY_BATCH);
        service = new NdasendaLoanApprovalServiceImpl(restTemplate, mock(NdasendaAuthServiceImpl.class), props,
                loanRepository, mock(LoanBatchService.class), notificationService);

        loan = Loan.builder().loanApprovalStatus(LoanApprovalStatus.PROCESSING)
                .mobileNumber("+263782606983").build();
        loan.setId(42L);
        when(loanRepository.findById(42L)).thenReturn(Optional.of(loan));
    }

    /** Ndasenda answers the day's batch with one deduction response for loan 42. */
    private void ndasendaResponds(NdasendaDeductionStatus status, String message) {
        NdasendaDeduction deduction = NdasendaDeduction.builder()
                .id("D1").reference("000000042").status(status).message(message).build();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class), any(Object[].class)))
                .thenAnswer(call -> ResponseEntity.ok(BY_DATE.equals(call.getArgument(0))
                        ? List.of(NdasendaDeductionsBatchRequest.builder().id("B1").build())
                        : List.of(NdasendaDeductionsBatchRequest.builder().id("B1")
                                .deductions(List.of(deduction)).build())));
    }

    @Test
    @DisplayName("an SSB rejection sends the fixed decline and keeps Ndasenda's reason on the loan")
    void ssbRejectionSendsNoUpstreamText() {
        ndasendaResponds(NdasendaDeductionStatus.FAILED, NDASENDA_REASON);

        service.processDeductionResponses(LocalDate.of(2026, 9, 23));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendSms(eq("+263782606983"), text.capture());
        assertThat(text.getValue())
                .isEqualTo("We regret to inform you that your loan application with ref # 000000042 "
                        + "has been declined. Please contact Innbucks for more information.")
                .doesNotContain("net salary");
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);
        assertThat(loan.getLoanStatusMessage()).isEqualTo(NDASENDA_REASON);
        verify(loanRepository).save(loan);
    }

    @Test
    @DisplayName("an SSB approval stays silent — the customer hears at credit sign-off")
    void ssbApprovalSendsNoSms() {
        ndasendaResponds(NdasendaDeductionStatus.SUCCESS, "Accepted");

        service.processDeductionResponses(LocalDate.of(2026, 9, 23));

        verify(notificationService, never()).sendSms(anyString(), anyString());
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(loan.getLoanStatusMessage()).isNull();
        verify(loanRepository).save(loan);
    }
}
