package zw.co.reikan.loans.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.reikan.loans.core.ManualDisbursementResult.Outcome;
import zw.co.reikan.loans.core.disbursements.BookingFailureKind;
import zw.co.reikan.loans.core.disbursements.LoanAccountCreationResponse;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatusResponse;
import zw.co.reikan.loans.core.exception.DisbursementNotAllowedException;
import zw.co.reikan.loans.core.exception.NotFoundException;
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

        service = new DisbursementService(loanRepository, notificationService, attemptRepository, transactionManager) {
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
    @DisplayName("an SMS failure after the payout does not undo the recorded SUCCESS")
    void anSmsFailureDoesNotUndoThePayout() {
        rail = request -> answer(DisbursementStatus.SUCCESS, "Approved");
        doThrow(new RuntimeException("gateway down")).when(notificationService).sendSms(anyString(), anyString());

        ManualDisbursementResult result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(Outcome.DISBURSED);
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.SUCCESS);
    }
}
