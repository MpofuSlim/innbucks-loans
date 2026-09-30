package zw.co.innbucks.loans.core.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.document.DocumentOrigin;
import zw.co.innbucks.loans.core.document.LoanDocumentRepository;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.LoanApprovalException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.user.User;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The payslip review queue (FR-SSB-007): what holding an application records, what the queue shows a
 * reviewer, and what each outcome does. Clearing lets a loan go on to be paid, so it is held to the same
 * segregation of duties as a credit approval; confirming rejects it, in the credit decision log, and a
 * loan held again after lodgement (an amended payslip, FR-SSB-009) has its SSB deduction queued for
 * cancellation.
 */
class PayslipReviewServiceTest {

    private LoanRepository loanRepository;
    private PayslipFraudFlagRepository flagRepository;
    private CreditDecisionRepository creditDecisionRepository;
    private LoanDocumentRepository loanDocumentRepository;
    private AuditService auditService;
    private LoanNotificationService loanNotificationService;
    private AuthService authService;
    private User reviewer;
    private PayslipReviewService service;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        flagRepository = mock(PayslipFraudFlagRepository.class);
        creditDecisionRepository = mock(CreditDecisionRepository.class);
        auditService = mock(AuditService.class);
        loanNotificationService = mock(LoanNotificationService.class);
        authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("credit.manager");
        reviewer = new User();
        reviewer.setUsername("credit.manager");
        reviewer.setIdNumber("639999999Z99");
        when(authService.getLoggedInUser()).thenReturn(reviewer);
        LoanMapper loanMapper = mock(LoanMapper.class);
        when(loanMapper.toResponse(any())).thenReturn(new LoanResponse());
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        loanDocumentRepository = mock(LoanDocumentRepository.class);
        service = new PayslipReviewService(loanRepository, flagRepository,
                new CreditDecisionLog(creditDecisionRepository, loanDocumentRepository), authService, loanMapper,
                loanNotificationService, auditService, new DeductionCancellationService(loanRepository, auditService,
                authService), loanDocumentRepository, mock(PlatformTransactionManager.class));
    }

    private static Loan loan(long id, PayslipReviewStatus review) {
        Loan loan = Loan.builder().loanApprovalStatus(LoanApprovalStatus.NEW)
                .internalApprovalStatus(InternalApprovalStatus.PENDING).payslipReviewStatus(review)
                .ecNumber("1234567A").nationalIdNumber("631234567A42").firstName("Rudo").lastName("Chikwanha")
                .mobileNumber("263782606983").disbursedAmount(new BigDecimal("500.00")).createdBy("agent.moyo")
                .build();
        loan.setId(id);
        return loan;
    }

    private Loan held(long id) {
        Loan loan = loan(id, PayslipReviewStatus.PENDING);
        when(loanRepository.findByIdForUpdate(id)).thenReturn(Optional.of(loan));
        return loan;
    }

    private List<AuditLog> audited() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    @Test
    @DisplayName("holding an application records every finding and audits the reasons, not the identities")
    void holdRecordsTheFindings() {
        Loan loan = loan(42, PayslipReviewStatus.PENDING);

        service.hold(loan, List.of(
                new PayslipFraudDetector.Finding(PayslipFraudReason.PAYSLIP_REUSED_BY_ANOTHER_APPLICANT, 17L,
                        "Same payslip file as loan 000000017"),
                new PayslipFraudDetector.Finding(PayslipFraudReason.DEDUCTIONS_EXCEED_GROSS_LESS_NET, null,
                        "Deductions total 450.00 but gross less net is 400.00")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PayslipFraudFlag>> flags = ArgumentCaptor.forClass(List.class);
        verify(flagRepository).saveAll(flags.capture());
        assertThat(flags.getValue()).extracting(PayslipFraudFlag::getLoanId, PayslipFraudFlag::getReason,
                        PayslipFraudFlag::getMatchedLoanId)
                .containsExactly(
                        tuple(42L, PayslipFraudReason.PAYSLIP_REUSED_BY_ANOTHER_APPLICANT, 17L),
                        tuple(42L, PayslipFraudReason.DEDUCTIONS_EXCEED_GROSS_LESS_NET, null));
        assertThat(flags.getValue()).allSatisfy(flag -> assertThat(flag.getCreatedAt()).isNotNull());
        AuditLog audit = audited().getFirst();
        assertThat(audit.getEventType()).isEqualTo("PAYSLIP_REVIEW_REQUIRED");
        assertThat(audit.getDetail()).contains("PAYSLIP_REUSED_BY_ANOTHER_APPLICANT", "matchedLoans=17")
                .doesNotContain("1234567A", "631234567A42");
    }

    @Test
    @DisplayName("the queue shows each held application with the other application behind each finding")
    void queueShowsTheMatches() {
        Loan heldLoan = loan(42, PayslipReviewStatus.PENDING);
        Loan earlier = loan(17, PayslipReviewStatus.CONFIRMED);
        earlier.setEcNumber("7654321B");
        earlier.setFirstName("Tatenda");
        when(loanRepository.findByPayslipReviewStatusOrderByIdAsc(PayslipReviewStatus.PENDING))
                .thenReturn(List.of(heldLoan));
        when(flagRepository.findByLoanIdInOrderByIdAsc(List.of(42L))).thenReturn(List.of(
                PayslipFraudFlag.builder().loanId(42L).reason(PayslipFraudReason.PAYSLIP_REUSED_BY_ANOTHER_APPLICANT)
                        .matchedLoanId(17L).detail("Same payslip file as loan 000000017").build()));
        when(loanRepository.findAllById(anyIterable())).thenReturn(List.of(earlier));

        List<PayslipReviewResponse> queue = service.queue();

        assertThat(queue).hasSize(1);
        assertThat(queue.getFirst().reference()).isEqualTo("000000042");
        PayslipFraudFlagResponse flag = queue.getFirst().flags().getFirst();
        assertThat(flag.reason()).isEqualTo(PayslipFraudReason.PAYSLIP_REUSED_BY_ANOTHER_APPLICANT);
        assertThat(flag.matchedReference()).isEqualTo("000000017");
        assertThat(flag.matchedEcNumber()).isEqualTo("7654321B");
        assertThat(flag.matchedFirstName()).isEqualTo("Tatenda");
        // The earlier application was itself confirmed as fraud: the strongest signal the queue can give.
        assertThat(flag.matchedPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.CONFIRMED);
    }

    @Test
    @DisplayName("an empty queue reads nothing more")
    void emptyQueue() {
        assertThat(service.queue()).isEmpty();
        verifyNoInteractions(flagRepository);
    }

    @Test
    @DisplayName("CLEARED releases the application to SSB lodgement, and tells the customer nothing")
    void clearReleasesTheLoan() {
        Loan loan = held(42);

        service.review(42L, new PayslipReviewRequest(PayslipReviewStatus.CLEARED, "  Re-application after June failure "));

        assertThat(loan.getPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.CLEARED);
        assertThat(loan.getPayslipReviewedBy()).isEqualTo("credit.manager");
        assertThat(loan.getPayslipReviewedAt()).isNotNull();
        assertThat(loan.getPayslipReviewComment()).isEqualTo("Re-application after June failure");
        assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.PENDING);
        verifyNoInteractions(creditDecisionRepository, loanNotificationService);
        assertThat(audited()).extracting(AuditLog::getEventType).containsExactly("PAYSLIP_REVIEW_CLEARED");
    }

    @Test
    @DisplayName("CONFIRMED rejects the application as suspected fraud, in the decision log, with the usual decline SMS")
    void confirmRejectsTheLoan() {
        Loan loan = held(42);

        service.review(42L, new PayslipReviewRequest(PayslipReviewStatus.CONFIRMED,
                "Same payslip as loan 17 under another EC number"));

        assertThat(loan.getPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.CONFIRMED);
        assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
        assertThat(loan.getInternalApprovalReasonCode()).isEqualTo("REJECT_SUSPECTED_FRAUD");
        assertThat(loan.getInternalApprovalBy()).isEqualTo("credit.manager");
        // Never lodged: there is no deduction to cancel.
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        ArgumentCaptor<CreditDecision> entry = ArgumentCaptor.forClass(CreditDecision.class);
        verify(creditDecisionRepository).save(entry.capture());
        assertThat(entry.getValue().getAction()).isEqualTo(CreditAction.REJECTED);
        assertThat(entry.getValue().getReasonCode()).isEqualTo("REJECT_SUSPECTED_FRAUD");
        assertThat(entry.getValue().getPerformedAt()).isEqualTo(loan.getPayslipReviewedAt());
        // The same decline as any other: nothing says why.
        ArgumentCaptor<Loan> declined = ArgumentCaptor.forClass(Loan.class);
        verify(loanNotificationService).notify(declined.capture(), eq(LoanNotice.DECLINED));
        assertThat(declined.getValue().getMobileNumber()).isEqualTo("263782606983");
        assertThat(LoanNotice.DECLINED.textFor(declined.getValue())).isEqualTo("We regret to inform you that your"
                + " loan application with ref # 000000042 has been declined. Please contact Innbucks for more information.");
        assertThat(audited()).extracting(AuditLog::getEventType).containsExactly("PAYSLIP_REVIEW_CONFIRMED");
    }

    @Test
    @DisplayName("a loan not waiting for review is a conflict, and nothing changes")
    void notPendingIsAConflict() {
        Loan loan = loan(42, PayslipReviewStatus.CLEARED);
        when(loanRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));

        assertThatThrownBy(() -> service.review(42L,
                new PayslipReviewRequest(PayslipReviewStatus.CONFIRMED, "Second look")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Loan 000000042 has no payslip review pending");
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(loanNotificationService, creditDecisionRepository);
    }

    @Test
    @DisplayName("an unknown loan is a 404")
    void unknownLoan() {
        when(loanRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(7L, new PayslipReviewRequest(PayslipReviewStatus.CLEARED, "ok")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 7 not found");
    }

    @Test
    @DisplayName("PENDING is not an outcome, and a comment is required")
    void outcomeAndCommentAreRequired() {
        assertThatThrownBy(() -> service.review(42L, new PayslipReviewRequest(PayslipReviewStatus.PENDING, "x")))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Invalid outcome: a review must be CLEARED or CONFIRMED");
        assertThatThrownBy(() -> service.review(42L, new PayslipReviewRequest(PayslipReviewStatus.CLEARED, " ")))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Comment is required");
        verify(loanRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("the originator cannot clear their own application")
    void originatorCannotClear() {
        Loan loan = held(42);
        loan.setCreatedBy("credit.manager");

        assertThatThrownBy(() -> service.review(42L, new PayslipReviewRequest(PayslipReviewStatus.CLEARED, "fine")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Loan 000000042 was originated by credit.manager, who cannot also clear its payslip review;"
                        + " another credit officer must");
        assertThat(loan.getPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.PENDING);
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("whoever replaced one of the loan's documents cannot clear its review")
    void amenderCannotClear() {
        Loan loan = held(42);
        when(loanDocumentRepository.existsByLoanIdAndOriginAndUploadedByIgnoreCase(
                42L, DocumentOrigin.AMENDMENT, "credit.manager")).thenReturn(true);

        assertThatThrownBy(() -> service.review(42L, new PayslipReviewRequest(PayslipReviewStatus.CLEARED, "fine")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Loan 000000042 has documents amended by credit.manager, who cannot also clear its"
                        + " payslip review; another credit officer must");
        assertThat(loan.getPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.PENDING);
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("CONFIRMED on a loan already lodged at SSB queues its deduction for cancellation")
    void confirmAfterLodgementQueuesTheCancellation() {
        // Held again by a payslip amended after SSB accepted the deduction.
        Loan loan = held(42);
        loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        loan.setBatchNumber("BATCH-20260901-07");

        service.review(42L, new PayslipReviewRequest(PayslipReviewStatus.CONFIRMED, "Amended payslip is forged"));

        assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo(DeductionCancellationService.REASON_CREDIT_REJECTED);
        assertThat(audited()).extracting(AuditLog::getEventType)
                .containsExactly("DEDUCTION_CANCELLATION_REQUIRED", "PAYSLIP_REVIEW_CONFIRMED");
    }

    @Test
    @DisplayName("a party to the application cannot clear it")
    void partyCannotClear() {
        held(42);
        reviewer.setIdNumber("63-1234567-A-42");

        assertThatThrownBy(() -> service.review(42L, new PayslipReviewRequest(PayslipReviewStatus.CLEARED, "fine")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("credit.manager is a party to loan 000000042 and cannot clear its payslip review;"
                        + " another credit officer must");
    }

    @Test
    @DisplayName("anyone may confirm: stopping a loan pays nobody")
    void originatorMayConfirm() {
        Loan loan = held(42);
        loan.setCreatedBy("credit.manager");

        service.review(42L, new PayslipReviewRequest(PayslipReviewStatus.CONFIRMED, "Payslip is not mine to vouch for"));

        assertThat(loan.getPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.CONFIRMED);
    }
}
