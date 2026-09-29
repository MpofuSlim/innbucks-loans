package zw.co.reikan.loans.core.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.exception.BusinessException;
import zw.co.reikan.loans.core.exception.LoanApprovalException;
import zw.co.reikan.loans.core.exception.NotFoundException;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.notifications.NotificationService;
import zw.co.reikan.loans.core.user.User;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The credit-manager refusals are TYPED so they reach the client as a 400/404
 * with their message. They were bare RuntimeExceptions — a 500 that read like a
 * server fault — and "already approved" was also the text for a REJECTED loan.
 */
class InternalApprovalServiceImplTest {

    private LoanRepository loanRepository;
    private AuditService auditService;
    private NotificationService notificationService;
    private InternalApprovalServiceImpl service;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        auditService = mock(AuditService.class);
        notificationService = mock(NotificationService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("credit.manager");
        service = new InternalApprovalServiceImpl(loanRepository, authService,
                mock(LoanMapper.class), notificationService,
                new DeductionCancellationService(loanRepository, auditService, authService), auditService);
    }

    private static InternalApprovalRequest decide(InternalApprovalStatus status) {
        InternalApprovalRequest request = new InternalApprovalRequest();
        request.setStatus(status);
        return request;
    }

    private Loan given(LoanApprovalStatus payroll, InternalApprovalStatus internal) {
        Loan loan = Loan.builder().loanApprovalStatus(payroll).internalApprovalStatus(internal)
                .batchNumber("BATCH-20260901-07").ecNumber("1234567A")
                .mobileNumber("+263782606983")
                .createdBy("agent.moyo")
                .merchant(Merchant.builder().merchantCode("INNBUCKS").companyName("Innbucks")
                        .disbursementType(DisbursementType.CUSTOMER_MOBILE_WALLET).build())
                .build();
        loan.setId(42L);
        when(loanRepository.findById(42L)).thenReturn(Optional.of(loan));
        return loan;
    }

    private List<AuditLog> audited() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    private String sentSms() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendSms(eq("+263782606983"), text.capture());
        return text.getValue();
    }

    @Test
    @DisplayName("an unknown loan is a NotFoundException (404), not NoSuchElementException (500)")
    void unknownLoanIsNotFound() {
        when(loanRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.APPROVED), 7L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 7 not found");
    }

    @Test
    @DisplayName("PENDING is not a decision")
    void pendingIsNotADecision() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.PENDING), 42L))
                .isInstanceOf(LoanApprovalException.class)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid status: a decision must be APPROVED or REJECTED");
    }

    @Test
    @DisplayName("a decided loan names the decision it already holds — approved OR rejected")
    void alreadyDecidedNamesTheDecision() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.REJECTED);

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Loan has already been rejected");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("a loan not yet payroll-approved cannot be signed off")
    void notPayrollApprovedIsRefused() {
        given(LoanApprovalStatus.NEW, InternalApprovalStatus.PENDING);

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Loan with status NEW cannot be approved");
    }

    @Test
    @DisplayName("a credit REJECT flags the lodged deduction for cancellation, audited, before the save")
    void creditRejectFlagsDeductionCancellation() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanRepository.save(any())).thenAnswer(i -> {
            // Saved together with the decision, not in a second write.
            assertThat(i.<Loan>getArgument(0).getDeductionCancellationStatus())
                    .isEqualTo(DeductionCancellationStatus.REQUIRED);
            return i.getArgument(0);
        });

        service.approveLoan(decide(InternalApprovalStatus.REJECTED), 42L);

        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("CREDIT_REJECTED");
        assertThat(loan.getDeductionCancellationRequestedAt()).isNotNull();
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(captor.capture());
        AuditLog audit = captor.getValue().build();
        assertThat(audit.getEventType()).isEqualTo("DEDUCTION_CANCELLATION_REQUIRED");
        assertThat(audit.getEntityId()).isEqualTo("42");
        assertThat(audit.getActorId()).isEqualTo("credit.manager");
        assertThat(audit.getCorrelationId()).isEqualTo("BATCH-20260901-07");
        assertThat(audit.getDetail()).contains("reason=CREDIT_REJECTED", "ecNumber=*****67A")
                .doesNotContain("1234567A");
    }

    @Test
    @DisplayName("a credit APPROVE leaves the deduction alone — it is what repays the loan")
    void creditApproveDoesNotFlag() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L);

        assertThat(loan.getDeductionCancellationStatus()).isNull();
        assertThat(audited()).extracting(AuditLog::getEventType).containsExactly("CREDIT_APPROVED");
    }

    @Test
    @DisplayName("a valid decision is saved")
    void validDecisionIsSaved() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        InternalApprovalResponse response = service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L);

        assertThat(response.getMessage()).isEqualTo("Approved successfully");
        verify(loanRepository).save(any());
    }

    @Test
    @DisplayName("a rejection answers with the decision recorded, not \"Approved successfully\"")
    void rejectionNamesTheDecision() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        InternalApprovalResponse response = service.approveLoan(decide(InternalApprovalStatus.REJECTED), 42L);

        assertThat(response.getMessage()).isEqualTo("Rejected successfully");
    }

    @Test
    @DisplayName("the reviewer's comment is kept on the loan and never sent to the customer")
    void rejectionSmsCarriesNoReviewerComment() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        InternalApprovalRequest request = decide(InternalApprovalStatus.REJECTED);
        request.setComment("Payslip looks edited: DTI 62%, do not re-apply!");

        service.approveLoan(request, 42L);

        assertThat(loan.getInternalApprovalComment()).isEqualTo("Payslip looks edited: DTI 62%, do not re-apply!");
        assertThat(sentSms())
                .isEqualTo("We regret to inform you that your loan application with ref # 000000042 "
                        + "has been declined. Please contact Innbucks for more information.")
                .doesNotContain("Payslip");
    }

    // ── Maker-checker ────────────────────────────────────────────────────────

    @Test
    @DisplayName("whoever originated a loan cannot approve it: refused (403) before anything is recorded")
    void originatorCannotApprove() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setCreatedBy("Credit.Manager");

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Loan 000000042 was originated by credit.manager, who cannot also approve it;"
                        + " another credit officer must");
        assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.PENDING);
        assertThat(loan.getApprovedDisbursementType()).isNull();
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(notificationService, auditService);
    }

    @Test
    @DisplayName("the originating user account counts too, whatever the createdBy text says")
    void originatingUserCannotApprove() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        User originator = new User();
        originator.setUsername("credit.manager");
        loan.setCreatedByUser(originator);

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L))
                .isInstanceOf(AccessDeniedException.class);
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("the originator may still reject: a refusal pays nothing")
    void originatorMayReject() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setCreatedBy("credit.manager");
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.approveLoan(decide(InternalApprovalStatus.REJECTED), 42L);

        assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
    }

    // ── The payee frozen at approval ─────────────────────────────────────────

    @Test
    @DisplayName("approval freezes the merchant's settlement account, and the audit names it masked")
    void approvalFreezesTheMerchantPayee() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setMerchant(Merchant.builder().merchantCode("MEGA").companyName("Mega Furnishers")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).accountNumber("0771000001").build());
        when(loanRepository.save(any())).thenAnswer(i -> {
            // Frozen in the same write as the decision.
            assertThat(i.<Loan>getArgument(0).getApprovedSettlementAccount()).isEqualTo("0771000001");
            return i.getArgument(0);
        });

        service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L);

        assertThat(loan.getApprovedDisbursementType()).isEqualTo(DisbursementType.MERCHANT_MOBILE_WALLET);
        assertThat(loan.getApprovedSettlementAccount()).isEqualTo("0771000001");
        AuditLog audit = audited().getFirst();
        assertThat(audit.getEventType()).isEqualTo("CREDIT_APPROVED");
        assertThat(audit.getActorId()).isEqualTo("credit.manager");
        assertThat(audit.getDetail()).contains("originator=agent.moyo", "payoutType=MERCHANT_MOBILE_WALLET",
                "settlementAccount=****0001", "merchant=MEGA").doesNotContain("0771000001");

        // A later edit to the merchant row does not move the loan's money.
        loan.getMerchant().setAccountNumber("0779999999");
        PayoutDestination payee = PayoutDestination.of(loan);
        assertThat(payee.merchantAccount()).isEqualTo("0771000001");
        assertThat(payee.frozen()).isTrue();
        assertThat(payee.differsFrom(loan.getMerchant())).isTrue();
    }

    @Test
    @DisplayName("a customer-wallet loan freezes the type and no merchant account")
    void approvalFreezesTheCustomerWallet() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L);

        assertThat(loan.getApprovedDisbursementType()).isEqualTo(DisbursementType.CUSTOMER_MOBILE_WALLET);
        assertThat(loan.getApprovedSettlementAccount()).isNull();
        assertThat(audited().getFirst().getDetail()).doesNotContain("settlementAccount");
    }

    @Test
    @DisplayName("a merchant loan with no settlement account cannot be approved: there is nowhere to pay it")
    void noSettlementAccountIsRefused() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setMerchant(Merchant.builder().companyName("Mega Furnishers")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).accountNumber(" ").build());

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Merchant Mega Furnishers has no settlement account, so loan 000000042 cannot be paid");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("a loan whose merchant has no payout type cannot be approved")
    void noPayoutTypeIsRefused() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setMerchant(null);

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Loan 000000042 has no merchant payout type, so there is nowhere to pay it");
    }

    @Test
    @DisplayName("a loan approved before the freeze existed still pays per the merchant's live settings")
    void legacyLoanFallsBackToTheLiveMerchant() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED);
        loan.setMerchant(Merchant.builder().disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET)
                .accountNumber("0771000001").build());

        PayoutDestination payee = PayoutDestination.of(loan);

        assertThat(payee.frozen()).isFalse();
        assertThat(payee.merchantAccount()).isEqualTo("0771000001");
        assertThat(payee.differsFrom(loan.getMerchant())).isFalse();
    }
}
