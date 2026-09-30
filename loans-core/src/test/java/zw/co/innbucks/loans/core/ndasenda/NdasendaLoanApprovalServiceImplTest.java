package zw.co.innbucks.loans.core.ndasenda;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.LoanBatchService;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;

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
    private LoanNotificationService loanNotificationService;
    private NdasendaLoanApprovalServiceImpl service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        loanRepository = mock(LoanRepository.class);
        loanNotificationService = mock(LoanNotificationService.class);
        AuditService auditService = mock(AuditService.class);
        NdasendaParameters props = new NdasendaParameters();
        props.setDeductionResponsesByDateRangeEndpoint(BY_DATE);
        props.setDeductionResponsesByBatchId(BY_BATCH);
        service = new NdasendaLoanApprovalServiceImpl(restTemplate, mock(NdasendaAuthService.class), props,
                loanRepository, mock(LoanBatchService.class), loanNotificationService, auditService,
                new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class)), new MarketTimeZone("ZW"));

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
                        ? List.of(NdasendaDeductionBatch.builder().id("B1").build())
                        : List.of(NdasendaDeductionBatch.builder().id("B1")
                                .deductions(List.of(deduction)).build())));
    }

    @Test
    @DisplayName("an SSB rejection sends the fixed decline and keeps Ndasenda's reason on the loan")
    void ssbRejectionSendsNoUpstreamText() {
        ndasendaResponds(NdasendaDeductionStatus.FAILED, NDASENDA_REASON);

        service.sweepDeductionResponses(LocalDate.of(2026, 9, 23).atTime(10, 0));

        verify(loanNotificationService).notify(loan, LoanNotice.DECLINED);
        assertThat(LoanNotice.DECLINED.textFor(loan))
                .isEqualTo("We regret to inform you that your loan application with ref # 000000042 "
                        + "has been declined. Please contact Innbucks for more information.")
                .doesNotContain("net salary");
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);
        assertThat(loan.getLoanStatusMessage()).isEqualTo(NDASENDA_REASON);
        verify(loanRepository).save(loan);
    }

    @Test
    @DisplayName("an SSB approval tells the applicant the deduction is confirmed, not that the loan is approved (FR-SSB-016)")
    void ssbApprovalSaysTheDeductionIsConfirmed() {
        ndasendaResponds(NdasendaDeductionStatus.SUCCESS, "Accepted");

        service.sweepDeductionResponses(LocalDate.of(2026, 9, 23).atTime(10, 0));

        verify(loanNotificationService).notify(loan, LoanNotice.SSB_CONFIRMED);
        assertThat(LoanNotice.SSB_CONFIRMED.textFor(loan)).contains("SSB has confirmed the salary deduction",
                "000000042", "being assessed").doesNotContain("approved");
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(loan.getLoanStatusMessage()).isNull();
        verify(loanRepository).save(loan);
    }
}
