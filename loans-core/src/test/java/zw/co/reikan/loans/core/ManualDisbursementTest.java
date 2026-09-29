package zw.co.reikan.loans.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.reikan.loans.core.ManualDisbursementResult.Outcome;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.disbursements.BookingFailureKind;
import zw.co.reikan.loans.core.disbursements.LoanAccountCreationResponse;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatusResponse;
import zw.co.reikan.loans.core.exception.DisbursementNotAllowedException;
import zw.co.reikan.loans.core.exception.NotFoundException;
import zw.co.reikan.loans.core.ledger.DisbursementLedger;
import zw.co.reikan.loans.core.ledger.LedgerAccount;
import zw.co.reikan.loans.core.ledger.LedgerEntryRepository;
import zw.co.reikan.loans.core.ledger.LedgerEntryType;
import zw.co.reikan.loans.core.ledger.LedgerService;
import zw.co.reikan.loans.core.ledger.LedgerService.Leg;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.DeductionCancellationStatus;
import zw.co.reikan.loans.core.loan.DisbursementStatus;
import zw.co.reikan.loans.core.loan.DisbursementType;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanDisbursement;
import zw.co.reikan.loans.core.loan.LoanDisbursementRepository;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@code POST /api/loans/{id}/disburse} is RECOVERY-ONLY: it may pay a loan only when the
 * pre-approved booking (which pays on its own) definitively did not, and it may never pay a
 * loan twice. The rail is a stub here; the attempt table is an in-memory list so a second
 * call sees exactly what the first one committed.
 */
class ManualDisbursementTest {

    private static final String STABLE_REF = "MD-000000042";

    private LoanRepository loanRepository;
    private LoanDisbursementRepository attemptRepository;
    private NotificationService notificationService;
    private PlatformTransactionManager transactionManager;
    private AuditService auditService;
    private LedgerService ledgerService;
    private final List<LoanDisbursement> attempts = new ArrayList<>();
    private final List<DisbursementRequest> sent = new ArrayList<>();
    private Function<DisbursementRequest, DisbursementResponse> rail;
    private DisbursementService service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        attemptRepository = mock(LoanDisbursementRepository.class);
        notificationService = mock(NotificationService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        auditService = mock(AuditService.class);
        ledgerService = mock(LedgerService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("ops.admin");

        when(attemptRepository.save(any())).thenAnswer(inv -> {
            LoanDisbursement row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId((long) attempts.size() + 1);
                attempts.add(row);
            }
            return row;
        });
        when(attemptRepository.findByLoanId(anyLong())).thenAnswer(inv -> List.copyOf(attempts));
        when(attemptRepository.findById(anyLong())).thenAnswer(inv -> attempts.stream()
                .filter(a -> a.getId().equals(inv.getArgument(0))).findFirst());

        service = new DisbursementService(loanRepository, notificationService, attemptRepository,
                new DeductionCancellationService(loanRepository, auditService, authService),
                new DisbursementLedger(ledgerService, mock(LedgerEntryRepository.class)), transactionManager) {
            @Override
            public DisbursementResponse disburseFunds(DisbursementRequest request) {
                sent.add(request);
                return rail.apply(request);
            }

            @Override
            public LoanAccountCreationResponse createLoanAccount(Loan loan) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanDisbursementStatusResponse checkLoanDisbursementStatus(Loan loan) {
                throw new UnsupportedOperationException();
            }
        };

        // Eligible: SSB + Credit approved, and InnBucks definitively refused the booking.
        loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .loanAccountStatus(LoanAccountStatus.FAILED)
                .disbursementStatus(LoanDisbursementStatus.FAILED)
                .bookingFailureKind(BookingFailureKind.REFUSED)
                .disbursedAmount(new BigDecimal("450.00"))
                .mobileNumber("0772123123")
                .merchant(Merchant.builder().companyName("Innbucks")
                        .disbursementType(DisbursementType.CUSTOMER_MOBILE_WALLET).accountNumber("999").build())
                .build();
        loan.setId(42L);
        when(loanRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));
    }

    private static DisbursementResponse answer(DisbursementStatus status, String message) {
        return DisbursementResponse.builder().status(status).approvalCode("AUTH-1").message(message).build();
    }

    private void assertRefusedBeforeAnythingWasSent(String reason) {
        assertThatThrownBy(() -> service.disburse(42L))
                .isInstanceOf(DisbursementNotAllowedException.class)
                .hasMessageContaining(reason);
        assertThat(sent).isEmpty();
        assertThat(attempts).isEmpty();
        verify(attemptRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("REFUSED booking + admin call: pays once, under the stable MD-<loan reference>")
    void refusedBookingIsPaidWithTheStableReference() {
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");

        ManualDisbursementResult result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(Outcome.DISBURSED);
        assertThat(result.getReference()).isEqualTo(STABLE_REF);
        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.getTransactionReference()).isEqualTo(STABLE_REF);
            assertThat(request.getReference()).isEqualTo("000000042");
            assertThat(request.getAmount()).isEqualByComparingTo("450.00");
            assertThat(request.getDisbursementType()).isEqualTo(DisbursementType.CUSTOMER_MOBILE_WALLET);
            assertThat(request.getMobileNumber()).isEqualTo("0772123123");
            assertThat(request.getAccountNumber()).isNull();
        });
        assertThat(attempts).singleElement().satisfies(row -> {
            assertThat(row.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.SUCCESS);
            assertThat(row.getDisbursementReference()).isEqualTo(STABLE_REF);
            assertThat(row.getDateDisbursed()).isNotNull();
        });
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.SUCCESS);
        assertThat(loan.getDisbursementReference()).isEqualTo(STABLE_REF);
        assertThat(loan.getDateDisbursed()).isNotNull();
        assertThat(loan.getDisbursementAttempts()).isEqualTo(1);
        verify(notificationService).sendSms(eq("0772123123"), anyString());
    }

    @Test
    @DisplayName("write-ahead: a PENDING row with the stable reference is COMMITTED before InnBucks is called")
    void theAttemptIsCommittedBeforeTheCall() {
        rail = request -> {
            assertThat(attempts).singleElement().satisfies(row -> {
                assertThat(row.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.PENDING);
                assertThat(row.getDisbursementReference()).isEqualTo(STABLE_REF);
            });
            verify(transactionManager, times(1)).commit(any());
            return answer(DisbursementStatus.SUCCESS, "Approved");
        };

        service.disburse(42L);

        verify(transactionManager, times(2)).commit(any());
    }

    @Test
    @DisplayName("refused: SSB has not approved the loan")
    void refusedWhenSsbHasNotApproved() {
        loan.setLoanApprovalStatus(LoanApprovalStatus.REJECTED);
        assertRefusedBeforeAnythingWasSent("not SSB-approved");
    }

    @Test
    @DisplayName("refused: Credit has not approved the loan (still awaiting review)")
    void refusedWhenCreditHasNotApproved() {
        loan.setInternalApprovalStatus(InternalApprovalStatus.PENDING);
        assertRefusedBeforeAnythingWasSent("not credit-approved");
    }

    @Test
    @DisplayName("refused: the pre-approved booking is CREATED/PENDING — InnBucks is already paying it")
    void refusedWhileTheBookingIsPaying() {
        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        loan.setDisbursementStatus(LoanDisbursementStatus.PENDING);
        loan.setBookingFailureKind(null);
        assertRefusedBeforeAnythingWasSent("has not definitively refused the booking");
    }

    @Test
    @DisplayName("refused: the booking's outcome is AMBIGUOUS — it may already have paid")
    void refusedWhenTheBookingIsAmbiguous() {
        loan.setBookingFailureKind(BookingFailureKind.AMBIGUOUS);
        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        loan.setDisbursementStatus(LoanDisbursementStatus.PENDING);
        assertRefusedBeforeAnythingWasSent("unknown outcome");
    }

    @Test
    @DisplayName("refused: a FAILED booking recorded before failures were classified proves nothing")
    void refusedWhenTheBookingFailureIsUnclassified() {
        loan.setBookingFailureKind(null);
        assertRefusedBeforeAnythingWasSent("has not definitively refused the booking");
    }

    @Test
    @DisplayName("refused: the loan is already disbursed")
    void refusedWhenAlreadyDisbursed() {
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        loan.setDisbursementReference("000000042");
        assertRefusedBeforeAnythingWasSent("already disbursed");
    }

    @Test
    @DisplayName("refused: an earlier manual attempt is still in doubt")
    void refusedWhileAnEarlierAttemptIsInDoubt() {
        LoanDisbursement inDoubt = new LoanDisbursement();
        inDoubt.setId(7L);
        inDoubt.setDisbursementStatus(LoanDisbursementStatus.PENDING);
        inDoubt.setDisbursementReference(STABLE_REF);
        when(attemptRepository.findByLoanId(42L)).thenReturn(List.of(inDoubt));

        assertThatThrownBy(() -> service.disburse(42L))
                .isInstanceOf(DisbursementNotAllowedException.class)
                .hasMessageContaining("is in doubt");
        assertThat(sent).isEmpty();
        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("refused: a FAILED attempt from before in-doubt tracking (other reference) may have paid")
    void refusedAfterALegacyFailedAttempt() {
        LoanDisbursement legacy = new LoanDisbursement();
        legacy.setId(7L);
        legacy.setDisbursementStatus(LoanDisbursementStatus.FAILED);
        legacy.setDisbursementReference(null);
        when(attemptRepository.findByLoanId(42L)).thenReturn(List.of(legacy));

        assertThatThrownBy(() -> service.disburse(42L))
                .isInstanceOf(DisbursementNotAllowedException.class)
                .hasMessageContaining("predates in-doubt tracking");
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("refused: a merchant loan whose merchant has no settlement account")
    void refusedWithoutAMerchantAccount() {
        loan.getMerchant().setDisbursementType(DisbursementType.MERCHANT_MOBILE_WALLET);
        loan.getMerchant().setAccountNumber(" ");
        assertRefusedBeforeAnythingWasSent("no settlement account");
    }

    @Test
    @DisplayName("an unknown loan is a NotFoundException (404), and nothing is sent")
    void unknownLoanIsNotFound() {
        assertThatThrownBy(() -> service.disburse(7L)).isInstanceOf(NotFoundException.class);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("a timeout leaves the attempt PENDING (in doubt), and a second call is refused")
    void aTimeoutIsInDoubtAndBlocksTheNextCall() {
        rail = request -> answer(DisbursementStatus.UNKNOWN, "I/O error: Read timed out");

        ManualDisbursementResult first = service.disburse(42L);

        assertThat(first.getOutcome()).isEqualTo(Outcome.IN_DOUBT);
        assertThat(first.getReference()).isEqualTo(STABLE_REF);
        assertThat(first.getMessage()).contains("Confirm with InnBucks whether " + STABLE_REF + " was paid");
        assertThat(attempts).singleElement().satisfies(row ->
                assertThat(row.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.PENDING));
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.FAILED);

        assertThatThrownBy(() -> service.disburse(42L))
                .isInstanceOf(DisbursementNotAllowedException.class)
                .hasMessageContaining("is in doubt");
        assertThat(sent).hasSize(1);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a rail that throws is in doubt too — the row stays PENDING")
    void anEscapedExceptionIsInDoubt() {
        rail = request -> {
            throw new IllegalStateException("boom");
        };

        ManualDisbursementResult result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(Outcome.IN_DOUBT);
        assertThat(attempts).singleElement().satisfies(row ->
                assertThat(row.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.PENDING));
    }

    @Test
    @DisplayName("a definite refusal marks the attempt FAILED; the retry reuses the SAME reference")
    void aDefiniteRefusalMayBeRetriedUnderTheSameReference() {
        rail = request -> answer(DisbursementStatus.FAILED, "responseCode 51 Insufficient funds");

        ManualDisbursementResult first = service.disburse(42L);

        assertThat(first.getOutcome()).isEqualTo(Outcome.REFUSED);
        assertThat(attempts).singleElement().satisfies(row ->
                assertThat(row.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.FAILED));

        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");
        ManualDisbursementResult second = service.disburse(42L);

        assertThat(second.getOutcome()).isEqualTo(Outcome.DISBURSED);
        assertThat(sent).extracting(DisbursementRequest::getTransactionReference).containsExactly(STABLE_REF, STABLE_REF);
        assertThat(attempts).extracting(LoanDisbursement::getDisbursementStatus)
                .containsExactly(LoanDisbursementStatus.FAILED, LoanDisbursementStatus.SUCCESS);
        assertThat(loan.getDisbursementAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a paid attempt blocks every later call")
    void aPaidAttemptBlocksTheNextCall() {
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");
        service.disburse(42L);

        assertThatThrownBy(() -> service.disburse(42L))
                .isInstanceOf(DisbursementNotAllowedException.class)
                .hasMessageContaining("already disbursed");
        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("a merchant (consumer-finance) loan pays the merchant's settlement account, not the customer")
    void aMerchantLoanPaysTheMerchant() {
        loan.getMerchant().setDisbursementType(DisbursementType.MERCHANT_MOBILE_WALLET);
        loan.getMerchant().setAccountNumber("123456789");
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");

        service.disburse(42L);

        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.getDisbursementType()).isEqualTo(DisbursementType.MERCHANT_MOBILE_WALLET);
            assertThat(request.getAccountNumber()).isEqualTo("123456789");
        });
        assertThat(loan.getDisbursementMerchantAccountNumber()).isEqualTo("123456789");
    }

    @Test
    @DisplayName("a recovery payout pays the settlement account frozen at credit approval, not the merchant's current one")
    void aRecoveryPayoutPaysTheAccountFrozenAtApproval() {
        loan.setApprovedDisbursementType(DisbursementType.MERCHANT_MOBILE_WALLET);
        loan.setApprovedSettlementAccount("123456789");
        // Edited after credit approved the loan.
        loan.getMerchant().setDisbursementType(DisbursementType.MERCHANT_MOBILE_WALLET);
        loan.getMerchant().setAccountNumber("999999999");
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");

        service.disburse(42L);

        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.getDisbursementType()).isEqualTo(DisbursementType.MERCHANT_MOBILE_WALLET);
            assertThat(request.getAccountNumber()).isEqualTo("123456789");
        });
        assertThat(loan.getDisbursementMerchantAccountNumber()).isEqualTo("123456789");
    }

    @Test
    @DisplayName("a loan frozen as a customer-wallet payout still pays the customer after its merchant is switched")
    void aFrozenCustomerWalletLoanStillPaysTheCustomer() {
        loan.setApprovedDisbursementType(DisbursementType.CUSTOMER_MOBILE_WALLET);
        loan.getMerchant().setDisbursementType(DisbursementType.MERCHANT_MOBILE_WALLET);
        loan.getMerchant().setAccountNumber("999999999");
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");

        service.disburse(42L);

        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.getDisbursementType()).isEqualTo(DisbursementType.CUSTOMER_MOBILE_WALLET);
            assertThat(request.getAccountNumber()).isNull();
        });
    }

    @Test
    @DisplayName("an SMS failure after the payout does not undo the recorded SUCCESS")
    void anSmsFailureDoesNotUndoThePayout() {
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");
        doThrow(new RuntimeException("gateway down")).when(notificationService).sendSms(anyString(), anyString());

        ManualDisbursementResult result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(Outcome.DISBURSED);
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.SUCCESS);
    }

    // --- Ndasenda deduction cancellation (#77) meets the recovery payout ---

    private void flagged(String reason) {
        loan.setBatchNumber("BATCH-20260901-07");
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.REQUIRED);
        loan.setDeductionCancellationReason(reason);
        loan.setDeductionCancellationRequestedAt(LocalDateTime.of(2026, 9, 20, 8, 30));
    }

    private List<AuditLog> audited() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    @Test
    @DisplayName("refused: the deduction was recorded cancelled at Ndasenda — paid now, the loan has no repayment")
    void refusedWhenTheDeductionWasCancelledAtNdasenda() {
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.CANCELLED_EXTERNALLY);
        loan.setDeductionCancelledBy("ops.clerk");
        assertRefusedBeforeAnythingWasSent("recorded as cancelled at Ndasenda by ops.clerk");
    }

    @Test
    @DisplayName("a flagged loan leaves the cancellation queue BEFORE InnBucks is called, audited with the payout")
    void theFlagComesOffBeforeTheCall() {
        flagged(DeductionCancellationService.REASON_BOOKING_FAILED);
        rail = request -> {
            // Committed with the claim: no operator can record the deduction cancelled mid-payout.
            assertThat(loan.getDeductionCancellationStatus()).isNull();
            verify(transactionManager, times(1)).commit(any());
            return answer(DisbursementStatus.SUCCESS, "Approved");
        };

        ManualDisbursementResult result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(Outcome.DISBURSED);
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        assertThat(loan.getDeductionCancellationReason()).isNull();
        assertThat(audited()).singleElement().satisfies(row -> {
            assertThat(row.getEventType()).isEqualTo("DEDUCTION_CANCELLATION_WITHDRAWN");
            assertThat(row.getActorId()).isEqualTo("ops.admin");
            assertThat(row.getDetail()).contains("reason=BOOKING_FAILED", "manualPayout=" + STABLE_REF);
        });
    }

    @Test
    @DisplayName("a refused payout puts the flag back with its original reason: nothing was paid")
    void aRefusedPayoutReflagsTheDeduction() {
        flagged(DeductionCancellationService.REASON_BOOKING_FAILED);
        rail = request -> answer(DisbursementStatus.FAILED, "Insufficient float");

        ManualDisbursementResult result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(Outcome.REFUSED);
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("BOOKING_FAILED");
        assertThat(audited()).extracting(AuditLog::getEventType)
                .containsExactly("DEDUCTION_CANCELLATION_WITHDRAWN", "DEDUCTION_CANCELLATION_REQUIRED");
    }

    @Test
    @DisplayName("a payout in doubt is flagged BOOKING_IN_DOUBT — the customer may hold the money, so check first")
    void aPayoutInDoubtIsFlaggedInDoubt() {
        flagged(DeductionCancellationService.REASON_BOOKING_FAILED);
        rail = request -> answer(DisbursementStatus.UNKNOWN, "Read timed out");

        ManualDisbursementResult result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(Outcome.IN_DOUBT);
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("BOOKING_IN_DOUBT");
    }

    @Test
    @DisplayName("a loan that was never flagged is not flagged by its payout, whatever the outcome")
    void anUnflaggedLoanStaysUnflagged() {
        rail = request -> answer(DisbursementStatus.UNKNOWN, "Read timed out");

        service.disburse(42L);

        assertThat(loan.getDeductionCancellationStatus()).isNull();
        verifyNoInteractions(auditService);
    }

    // ── Ledger ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a paid recovery payout is posted with its SUCCESS, under DISB-MD-<ref>, with the fee as income")
    void aPaidRecoveryPayoutIsPostedWithItsSuccess() {
        loan.setPrincipal(new BigDecimal("500.00"));
        loan.setFeeAmount(new BigDecimal("50.00"));
        rail = request -> {
            verifyNoInteractions(ledgerService); // nothing is posted before the money moves
            return answer(DisbursementStatus.SUCCESS, "Approved");
        };

        service.disburse(42L);

        // Between the settle transaction's start and its commit: the payout and its posting commit together.
        var inOrder = inOrder(transactionManager, ledgerService);
        inOrder.verify(transactionManager).commit(any()); // the claim
        inOrder.verify(ledgerService).post(eq("DISB-MD-000000042"), eq(42L), eq("USD"), anyString(),
                eq("manual-payout"), eq(List.of(
                        new Leg(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE, LedgerEntryType.DEBIT, new BigDecimal("500.00")),
                        new Leg(LedgerAccount.DISBURSEMENT_CLEARING, LedgerEntryType.CREDIT, new BigDecimal("450.00")),
                        new Leg(LedgerAccount.FEE_INCOME, LedgerEntryType.CREDIT, new BigDecimal("50.00")))));
        inOrder.verify(transactionManager).commit(any()); // the settle
    }

    @Test
    @DisplayName("a refused or in-doubt recovery payout posts nothing: no money is known to have moved")
    void anUnpaidRecoveryPayoutPostsNothing() {
        rail = request -> answer(DisbursementStatus.FAILED, "Insufficient float");
        service.disburse(42L);

        rail = request -> answer(DisbursementStatus.UNKNOWN, "Read timed out");
        service.disburse(42L);

        verifyNoInteractions(ledgerService);
    }
}
