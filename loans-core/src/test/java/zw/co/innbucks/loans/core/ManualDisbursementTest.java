package zw.co.innbucks.loans.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.innbucks.loans.core.notice.LoanNotificationSender;
import zw.co.innbucks.loans.core.notice.LoanNotificationRepository;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.ManualDisbursementResponse.Outcome;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.BookingFailureKind;
import zw.co.innbucks.loans.core.disbursements.LoanAccountCreationResponse;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatusResponse;
import zw.co.innbucks.loans.core.exception.DisbursementNotAllowedException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.ledger.DisbursementLedger;
import zw.co.innbucks.loans.core.ledger.LedgerAccount;
import zw.co.innbucks.loans.core.ledger.LedgerEntryRepository;
import zw.co.innbucks.loans.core.ledger.LedgerEntryType;
import zw.co.innbucks.loans.core.ledger.LedgerService;
import zw.co.innbucks.loans.core.ledger.LedgerService.Leg;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.DeductionCancellationStatus;
import zw.co.innbucks.loans.core.loan.DisbursementStatus;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanDisbursement;
import zw.co.innbucks.loans.core.loan.LoanDisbursementRepository;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.workflow.AssignmentMode;
import zw.co.innbucks.loans.core.workflow.CheckpointDecision;
import zw.co.innbucks.loans.core.workflow.CheckpointDecisionRepository;
import zw.co.innbucks.loans.core.workflow.CheckpointGate;
import zw.co.innbucks.loans.core.workflow.CheckpointOutcome;
import zw.co.innbucks.loans.core.workflow.HoldPoint;
import zw.co.innbucks.loans.core.workflow.StageKind;
import zw.co.innbucks.loans.core.workflow.WorkAssignmentGuard;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;
import zw.co.innbucks.loans.core.workflow.WorkflowStageRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
    /** When the payout authorisation was switched on, and, three days later, when the loan's booking was sent. */
    private static final LocalDateTime SWITCHED_ON = LocalDateTime.of(2026, 10, 2, 7, 15, 4);
    private static final LocalDateTime BOOKED_AT = LocalDateTime.of(2026, 10, 5, 8, 0);

    /** InnBucks' deposit endpoint as the rail reaches it: a refusal must leave it untouched. */
    interface InnbucksDeposit {
        DisbursementResponse deposit(DisbursementRequest request);
    }

    private LoanRepository loanRepository;
    private LoanDisbursementRepository attemptRepository;
    private LoanNotificationService loanNotificationService;
    private PlatformTransactionManager transactionManager;
    private AuditService auditService;
    private LedgerService ledgerService;
    private AuthService authService;
    private final List<LoanDisbursement> attempts = new ArrayList<>();
    private final List<DisbursementRequest> sent = new ArrayList<>();
    private final List<WorkflowStage> checkpoints = new ArrayList<>();
    private final List<CheckpointDecision> checkpointDecisions = new ArrayList<>();
    private CheckpointGate checkpointGate;
    private InnbucksDeposit innbucks;
    private Function<DisbursementRequest, DisbursementResponse> rail;
    private DisbursementService service;
    private Loan loan;

    /** The service over the stubbed rail, telling the applicant through these notifications. */
    private DisbursementService service(LoanNotificationService notifications) {
        return new DisbursementService(loanRepository, notifications, attemptRepository,
                new DeductionCancellationService(loanRepository, auditService, authService,
                        mock(WorkAssignmentGuard.class), new MarketTimeZone("ZW")),
                new DisbursementLedger(ledgerService, mock(LedgerEntryRepository.class)), checkpointGate, authService,
                transactionManager) {
            @Override
            public DisbursementResponse disburseFunds(DisbursementRequest request) {
                sent.add(request);
                return innbucks.deposit(request);
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
    }

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        attemptRepository = mock(LoanDisbursementRepository.class);
        loanNotificationService = mock(LoanNotificationService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        auditService = mock(AuditService.class);
        ledgerService = mock(LedgerService.class);
        authService = mock(AuthService.class);
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

        // The real gate, over its two queries answered from memory the way the database answers them.
        WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
        when(stages.findByKindAndHoldPointAndActiveTrueOrderByDisplayOrderAscCodeAsc(eq(StageKind.CHECKPOINT), any()))
                .thenAnswer(inv -> checkpoints.stream()
                        .filter(stage -> stage.isActive() && stage.getHoldPoint() == inv.getArgument(1))
                        .toList());
        CheckpointDecisionRepository decisions = mock(CheckpointDecisionRepository.class);
        when(decisions.findByStageCodeInAndLoanIdIn(anyCollection(), anyCollection())).thenAnswer(inv -> {
            Collection<String> codes = inv.getArgument(0);
            Collection<Long> loanIds = inv.getArgument(1);
            return checkpointDecisions.stream()
                    .filter(decision -> codes.contains(decision.getStageCode())
                            && loanIds.contains(decision.getLoanId()))
                    .toList();
        });
        checkpointGate = new CheckpointGate(stages, loanRepository, decisions);

        innbucks = mock(InnbucksDeposit.class);
        when(innbucks.deposit(any())).thenAnswer(inv -> rail.apply(inv.getArgument(0)));

        service = service(loanNotificationService);

        // Eligible: SSB + Credit approved (by a credit manager, not the caller), and InnBucks definitively
        // refused the booking it was sent for.
        loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .internalApprovalBy("cmanager")
                .bookingClaimedAt(BOOKED_AT)
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
        verify(innbucks, never()).deposit(any());
        verify(attemptRepository, never()).save(any());
        verifyNoInteractions(loanNotificationService);
    }

    @Test
    @DisplayName("a customer payout goes to the loan's wallet number when it differs from the mobile number")
    void payoutGoesToTheWalletNumber() {
        loan.setWalletNumber("263712345678");
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");

        service.disburse(42L);

        assertThat(sent).singleElement()
                .satisfies(request -> assertThat(request.getMobileNumber()).isEqualTo("263712345678"));
    }

    @Test
    @DisplayName("REFUSED booking + admin call: pays once, under the stable MD-<loan reference>")
    void refusedBookingIsPaidWithTheStableReference() {
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");

        ManualDisbursementResponse result = service.disburse(42L);

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
        verify(loanNotificationService).notify(eq(loan), eq(LoanNotice.PAID), anyString());
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

        ManualDisbursementResponse first = service.disburse(42L);

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
        verifyNoInteractions(loanNotificationService);
    }

    @Test
    @DisplayName("a rail that throws is in doubt too — the row stays PENDING")
    void anEscapedExceptionIsInDoubt() {
        rail = request -> {
            throw new IllegalStateException("boom");
        };

        ManualDisbursementResponse result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(Outcome.IN_DOUBT);
        assertThat(attempts).singleElement().satisfies(row ->
                assertThat(row.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.PENDING));
    }

    @Test
    @DisplayName("a definite refusal marks the attempt FAILED; the retry reuses the SAME reference")
    void aDefiniteRefusalMayBeRetriedUnderTheSameReference() {
        rail = request -> answer(DisbursementStatus.FAILED, "responseCode 51 Insufficient funds");

        ManualDisbursementResponse first = service.disburse(42L);

        assertThat(first.getOutcome()).isEqualTo(Outcome.REFUSED);
        assertThat(attempts).singleElement().satisfies(row ->
                assertThat(row.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.FAILED));

        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");
        ManualDisbursementResponse second = service.disburse(42L);

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
        // The real notification service, over a sender that fails.
        LoanNotificationSender failing = mock(LoanNotificationSender.class);
        doThrow(new RuntimeException("gateway down")).when(failing).deliver(any());
        service = service(new LoanNotificationService(failing, mock(LoanNotificationRepository.class), loanRepository));

        ManualDisbursementResponse result = service.disburse(42L);

        verify(failing).deliver(any());
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
        // Stored in UTC, with the microseconds a database timestamp carries; told at the market's clock, to the second.
        loan.setDeductionCancelledAt(LocalDateTime.of(2026, 9, 30, 18, 22, 9, 123_456_000));
        assertRefusedBeforeAnythingWasSent("The payroll deduction of loan 000000042 was recorded as cancelled at"
                + " Ndasenda by ops.clerk at 2026-09-30T20:22:09+02:00; paid now, the loan would have no repayment."
                + " A manual payout is not allowed");
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

        ManualDisbursementResponse result = service.disburse(42L);

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

        ManualDisbursementResponse result = service.disburse(42L);

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

        ManualDisbursementResponse result = service.disburse(42L);

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

    // ── Checkpoints before booking and the second person (FR-SSB-014, FR-SSB-018) ──

    /** An active checkpoint before booking, switched on at {@code activeSince}, for every loan. */
    private WorkflowStage checkpoint(String code, String name, LocalDateTime activeSince) {
        WorkflowStage stage = WorkflowStage.builder().code(code).kind(StageKind.CHECKPOINT).name(name)
                .displayOrder(HoldPoint.BEFORE_BOOKING.displayOrder()).assignment(AssignmentMode.OPTIONAL)
                .targetHours(4).escalationHours(8).holdPoint(HoldPoint.BEFORE_BOOKING)
                .active(true).activeSince(activeSince).updatedBy("admin").updatedAt(activeSince).build();
        checkpoints.add(stage);
        return stage;
    }

    /** PAYOUT_AUTHORISATION as V14 seeds it, switched on before the loan's booking was sent. */
    private WorkflowStage payoutAuthorisation() {
        return checkpoint("PAYOUT_AUTHORISATION", "Payout authorisation", SWITCHED_ON);
    }

    private void decided(WorkflowStage stage, CheckpointOutcome outcome) {
        checkpointDecisions.add(CheckpointDecision.builder().stageCode(stage.getCode()).loanId(42L)
                .enteredAt(SWITCHED_ON).outcome(outcome).comment("Checked against the approval")
                .decidedBy("finance1").decidedAt(BOOKED_AT.minusHours(1)).build());
    }

    private void assertForbiddenBeforeAnythingWasSent(String reason) {
        assertThatThrownBy(() -> service.disburse(42L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage(reason);
        assertThat(sent).isEmpty();
        assertThat(attempts).isEmpty();
        verify(innbucks, never()).deposit(any());
        verify(attemptRepository, never()).save(any());
        // Refused inside the claim, under the loan's lock: the claim transaction rolled back, nothing committed.
        verify(transactionManager, never()).commit(any());
        verifyNoInteractions(loanNotificationService);
    }

    @Test
    @DisplayName("refused: payout authorisation was on when the booking was sent and has not cleared the loan")
    void refusedWhileWaitingForPayoutAuthorisation() {
        payoutAuthorisation();

        assertRefusedBeforeAnythingWasSent("Loan 000000042 is waiting for Payout authorisation, which was switched on"
                + " before its booking was sent and has not cleared it. A manual payout is not allowed");
    }

    @Test
    @DisplayName("refused: a checkpoint before booking declined the loan, even where its credit status still says APPROVED")
    void refusedWhenDeclinedAtPayoutAuthorisation() {
        // A decline is a credit rejection, so this loan would normally also fail "not credit-approved"; the decline
        // is its own refusal in case the status was ever put back by hand.
        decided(payoutAuthorisation(), CheckpointOutcome.DECLINED);

        assertRefusedBeforeAnythingWasSent("Loan 000000042 was declined at Payout authorisation."
                + " A manual payout is not allowed");
    }

    @Test
    @DisplayName("refused: every checkpoint before booking must have cleared it, an operator's own as well as the payout authorisation")
    void refusedUntilEveryCheckpointHasCleared() {
        decided(payoutAuthorisation(), CheckpointOutcome.CLEARED);
        WorkflowStage highValue = checkpoint("HIGH_VALUE_PAYOUT", "High-value payout check", SWITCHED_ON.minusDays(30));

        assertRefusedBeforeAnythingWasSent("is waiting for High-value payout check");

        decided(highValue, CheckpointOutcome.CLEARED);
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");
        assertThat(service.disburse(42L).getOutcome()).isEqualTo(Outcome.DISBURSED);
        verify(innbucks).deposit(any());
    }

    @Test
    @DisplayName("cleared at payout authorisation: paid as before")
    void paidOncePayoutAuthorisationHasCleared() {
        decided(payoutAuthorisation(), CheckpointOutcome.CLEARED);
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");

        ManualDisbursementResponse result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(Outcome.DISBURSED);
        assertThat(sent).singleElement()
                .satisfies(request -> assertThat(request.getTransactionReference()).isEqualTo(STABLE_REF));
        verify(innbucks, times(1)).deposit(any());
    }

    @Test
    @DisplayName("a checkpoint switched off, or one that does not apply to the loan or sits at another point, holds nothing")
    void checkpointsThatDoNotHoldTheLoanAreIgnored() {
        payoutAuthorisation().setActive(false);
        WorkflowStage large = checkpoint("LARGE_PAYOUT", "Large payout check", SWITCHED_ON);
        large.setMinimumPrincipal(new BigDecimal("2000.00"));
        loan.setPrincipal(new BigDecimal("500.00"));
        WorkflowStage ussd = checkpoint("USSD_PAYOUT", "USSD payout check", SWITCHED_ON);
        ussd.setChannels(Set.of("USSD"));
        WorkflowStage earlier = checkpoint("SECOND_LOOK", "Second look", SWITCHED_ON);
        earlier.setHoldPoint(HoldPoint.BEFORE_CREDIT_APPROVAL);
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");

        assertThat(service.disburse(42L).getOutcome()).isEqualTo(Outcome.DISBURSED);
        verify(innbucks, times(1)).deposit(any());
    }

    @Test
    @DisplayName("a checkpoint switched on after the booking was sent never held the loan, so it does not hold its payout")
    void aCheckpointSwitchedOnAfterTheBookingDoesNotHoldThePayout() {
        // The loan was past the point when it came on: it cannot wait or be cleared there, and the booking would have
        // paid it without this checkpoint had InnBucks accepted it.
        checkpoint("PAYOUT_AUTHORISATION", "Payout authorisation", BOOKED_AT.plusSeconds(1));
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");

        assertThat(service.disburse(42L).getOutcome()).isEqualTo(Outcome.DISBURSED);
        verify(innbucks, times(1)).deposit(any());
    }

    @Test
    @DisplayName("a booking refused before claims were recorded predates every checkpoint: none holds its payout")
    void aBookingWithNoRecordedClaimPredatesCheckpoints() {
        loan.setBookingClaimedAt(null);
        payoutAuthorisation();
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");

        assertThat(service.disburse(42L).getOutcome()).isEqualTo(Outcome.DISBURSED);
        verify(innbucks, times(1)).deposit(any());
    }

    @Test
    @DisplayName("403: whoever approved the loan at Credit cannot pay it; another SUPER_ADMIN can")
    void theCreditApproverCannotPayTheLoan() {
        decided(payoutAuthorisation(), CheckpointOutcome.CLEARED);
        loan.setInternalApprovalBy("OPS.Admin");

        assertForbiddenBeforeAnythingWasSent(
                "Loan 000000042 was approved by ops.admin, who cannot also pay it out; another SUPER_ADMIN must");

        when(authService.getLoggedInUsername()).thenReturn("second.admin");
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");
        assertThat(service.disburse(42L).getOutcome()).isEqualTo(Outcome.DISBURSED);
        verify(innbucks, times(1)).deposit(any());
    }

    @Test
    @DisplayName("403: whoever originated the loan cannot pay it, as they could not decide its payout authorisation")
    void theOriginatorCannotPayTheLoan() {
        loan.setCreatedBy("ops.admin");

        assertForbiddenBeforeAnythingWasSent(
                "Loan 000000042 was originated by ops.admin, who cannot also pay it out; another SUPER_ADMIN must");
    }

    @Test
    @DisplayName("403: a party to the loan (here the holder of the wallet it pays) cannot pay it")
    void aPartyToTheLoanCannotPayIt() {
        User caller = new User();
        caller.setUsername("ops.admin");
        caller.setMobileNumber("+263 77 212 3123");
        when(authService.getLoggedInUser()).thenReturn(caller);

        assertForbiddenBeforeAnythingWasSent(
                "ops.admin is a party to loan 000000042 and cannot pay it out; another SUPER_ADMIN must");
    }
}
