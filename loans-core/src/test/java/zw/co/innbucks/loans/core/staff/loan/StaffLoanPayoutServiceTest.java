package zw.co.innbucks.loans.core.staff.loan;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.BookingNotBuiltException;
import zw.co.innbucks.loans.core.disbursements.BookingNotSentException;
import zw.co.innbucks.loans.core.disbursements.InnbucksDisbursementService;
import zw.co.innbucks.loans.core.disbursements.PreApprovedBookingResult;
import zw.co.innbucks.loans.core.disbursements.PreApprovedLoanBooking;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutService.Outcome;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paying a cash Staff Grocery Loan: claimed under the register lock and the loan's row lock, sent once with no
 * transaction open, and settled only while the loan still carries the claim. Each answer the bank can give lands on
 * one payout status and none cancels the loan; a guard that refuses leaves it READY with why. The clock
 * stands at 29 October 2026, 10:00 in Harare; the loan is due on 20 November.
 */
class StaffLoanPayoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00.123456789Z");
    /** The claim's time, as it survives the round trip to a TIMESTAMP(6) column. */
    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 10, 29, 8, 0, 0, 123_456_000);

    private final StaffLoanRepository loans = mock(StaffLoanRepository.class);
    private final StaffMemberRepository members = mock(StaffMemberRepository.class);
    private final InnbucksDisbursementService bank = mock(InnbucksDisbursementService.class);
    private final AuditService audit = mock(AuditService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final StaffLoanPayoutMetrics metrics = new StaffLoanPayoutMetrics(registry);
    private final CountingTransactionManager transactions = new CountingTransactionManager();
    private final StaffLoanProperties properties = new StaffLoanProperties();
    private final StaffLoanPayoutService service;

    private StaffLoan loan = cashLoan(StaffLoanPayoutStatus.READY).build();
    private final StaffMember member = StaffMember.builder().id(2L).employeeNumber("E1012").fullName("Chipo Banda")
            .nationalId("632345678B42").msisdn("263772123123").grade("C4")
            .employmentStatus(StaffEmploymentStatus.ACTIVE).engagementDate(LocalDate.of(2015, 2, 2))
            .walletAccountNumber("263772123123").updatedBatchId(17L).build();

    /** What the bank answers, and what was true while it was being asked. */
    private PreApprovedBookingResult answer = new PreApprovedBookingResult(201, "000", "{\"responseCode\":\"000\"}",
            null);
    private final List<Boolean> transactionOpenDuringCall = new ArrayList<>();
    private final List<StaffLoanPayoutStatus> payoutDuringCall = new ArrayList<>();
    private final List<LocalDateTime> claimDuringCall = new ArrayList<>();
    private Runnable duringCall = () -> {
    };

    StaffLoanPayoutServiceTest() {
        properties.getPayout().setMethod(StaffLoanPayoutMethod.CASH);
        properties.getPayout().setDestination(StaffLoanPayoutDestination.VEENGU_WALLET);
        properties.getPayout().setProduct(" STAFFGROC ");
        service = new StaffLoanPayoutService(loans, members, properties, bank, metrics, audit,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)), transactions);
        when(loans.lockById(143L)).thenAnswer(i -> Optional.ofNullable(loan));
        when(loans.save(any())).thenAnswer(i -> i.getArgument(0));
        when(members.findById(2L)).thenReturn(Optional.of(member));
        when(bank.bookPreApproved(any())).thenAnswer(i -> {
            transactionOpenDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            payoutDuringCall.add(loan.getPayoutStatus());
            claimDuringCall.add(loan.getPayoutClaimedAt());
            duringCall.run();
            return answer;
        });
    }

    private static StaffLoan.StaffLoanBuilder cashLoan(StaffLoanPayoutStatus payout) {
        return StaffLoan.builder().id(143L).reference("SGL-2026-000143").staffMemberId(2L).offerId(31L)
                .employeeNumber("E1012").fullName("Chipo  Rudo Banda").msisdn("263772123123").grade("C4")
                .amount(new BigDecimal("150.00")).currency("USD").interestRate(BigDecimal.ZERO)
                .totalRepayable(new BigDecimal("150.00")).dueDate(LocalDate.of(2026, 11, 20))
                .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS)
                .status(StaffLoanStatus.AWAITING_DISBURSEMENT).acceptedAt(LocalDateTime.of(2026, 10, 29, 7, 50))
                .payoutMethod(StaffLoanPayoutMethod.CASH).payoutDestination(StaffLoanPayoutDestination.VEENGU_WALLET)
                .payoutAccount("263772123123").payoutStatus(payout)
                .payoutClaimedAt(payout == StaffLoanPayoutStatus.SENDING ? CLAIMED_AT.minusMinutes(5) : null);
    }

    private PreApprovedLoanBooking sent() {
        ArgumentCaptor<PreApprovedLoanBooking> captor = ArgumentCaptor.forClass(PreApprovedLoanBooking.class);
        verify(bank).bookPreApproved(captor.capture());
        return captor.getValue();
    }

    private List<AuditLog> audited() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit, org.mockito.Mockito.atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    private double counted(StaffLoanPayoutMetrics.Event event) {
        return metrics.counted(event);
    }

    @Test
    @DisplayName("a 2xx: claimed and committed before the call, sent with no transaction open, then BOOKED, seen by the"
            + " bank, its answer kept; audited and counted; the loan still awaits disbursement")
    void booked() {
        Outcome outcome = service.payOut(143L);

        assertThat(outcome).isEqualTo(Outcome.BOOKED);
        assertThat(transactionOpenDuringCall).containsExactly(false);
        assertThat(payoutDuringCall).as("claimed and committed before the call").containsExactly(
                StaffLoanPayoutStatus.SENDING);
        assertThat(claimDuringCall).containsExactly(CLAIMED_AT);
        assertThat(transactions.committed()).as("the claim, then the settle").isEqualTo(2);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(loan.getPayoutClaimedAt()).isNull();
        assertThat(loan.getPayoutBankSeenAt()).isEqualTo(LocalDateTime.of(2026, 10, 29, 8, 0, 0, 123_456_789));
        assertThat(loan.getPayoutLastAnswer()).isEqualTo("{\"responseCode\":\"000\"}");
        assertThat(loan.getPayoutApplyAnswer()).as("kept apart from the inquiry's answers")
                .isEqualTo("{\"responseCode\":\"000\"}");
        assertThat(loan.getPayoutMessage()).isNull();
        assertThat(counted(StaffLoanPayoutMetrics.Event.BOOKED)).isEqualTo(1);
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutService.BOOKED);
            assertThat(entry.getEntityType()).isEqualTo("STAFF_LOAN");
            assertThat(entry.getEntityId()).isEqualTo("143");
            assertThat(entry.getActorId()).isEqualTo(StaffLoanPayoutService.SYSTEM_ACTOR);
            assertThat(entry.getDetail()).isEqualTo("reference:SGL-2026-000143;http:201;responseCode:000;claimedAt:"
                    + CLAIMED_AT + ";account:****3123");
            assertThat(entry.getDetail()).doesNotContain("263772123123");
        });
        assertThat(outcome.carriesOn()).isTrue();
    }

    @Test
    @DisplayName("the register lock is taken before the loan's row lock, the order every register writer takes them in")
    void registerLockFirst() {
        service.payOut(143L);

        InOrder order = inOrder(members, loans);
        order.verify(members).lockRegister(StaffRegisterService.REGISTER_LOCK);
        order.verify(loans).lockById(143L);
        order.verify(members).findById(2L);
    }

    @ParameterizedTest(name = "HTTP {0} with no envelope is BOOKED")
    @ValueSource(ints = {200, 201, 202, 204})
    void twoHundredsAreBooked(int status) {
        assertOutcome(status, Outcome.BOOKED, StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getPayoutBankSeenAt()).isNotNull();
    }

    @ParameterizedTest(name = "a 2xx whose envelope says responseCode {0} is BOOKED")
    @ValueSource(strings = {"000", "0", "00"})
    void approvedEnvelopesAreBooked(String code) {
        answer = new PreApprovedBookingResult(200, code, "{\"responseCode\":\"" + code + "\"}", null);

        assertThat(service.payOut(143L)).isEqualTo(Outcome.BOOKED);
        assertThat(loan.getPayoutBankSeenAt()).isNotNull();
    }

    @ParameterizedTest(name = "a 2xx whose envelope says responseCode {0} is IN_DOUBT, not seen by the bank")
    @ValueSource(strings = {"05", "51", "004", "999", "Declined"})
    void otherEnvelopesInATwoHundredAreInDoubt(String code) {
        answer = new PreApprovedBookingResult(200, code, "{\"responseCode\":\"" + code + "\"}", null);

        assertThat(service.payOut(143L)).isEqualTo(Outcome.IN_DOUBT);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(loan.getPayoutBankSeenAt()).as("the bank's 2xx did not say it booked it").isNull();
        assertThat(loan.getPayoutMessage()).contains("HTTP 200 with responseCode " + code)
                .contains("never sent again");
        assertThat(loan.getPayoutApplyAnswer()).contains(code);
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutService.IN_DOUBT);
            assertThat(entry.getDetail()).contains("http:200;responseCode:" + code);
        });
    }

    @ParameterizedTest(name = "HTTP {0} was not processed: READY again, never claimed, and a pass stops")
    @ValueSource(ints = {401, 407, 425, 429})
    void notProcessedIsReleased(int status) {
        assertOutcome(status, Outcome.NOT_SENT, StaffLoanPayoutStatus.READY);
        assertThat(loan.getPayoutClaimedAt()).isNull();
        assertThat(loan.getPayoutMessage()).contains("HTTP " + status).contains("ready to pay");
        assertThat(loan.getPayoutBankSeenAt()).isNull();
        assertThat(Outcome.NOT_SENT.carriesOn()).isFalse();
    }

    @Test
    @DisplayName("a 403 was not processed, for a reason that may be this request's own: READY again, and a pass goes"
            + " on")
    void forbiddenIsPassedOver() {
        answer = new PreApprovedBookingResult(403, null, "<html>Request blocked</html>", null);

        Outcome outcome = service.payOut(143L);

        assertThat(outcome).isEqualTo(Outcome.PASSED_OVER);
        assertThat(outcome.carriesOn()).isTrue();
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
        assertThat(loan.getPayoutClaimedAt()).isNull();
        assertThat(loan.getPayoutMessage()).contains("HTTP 403").contains("ready to pay");
        assertThat(counted(StaffLoanPayoutMetrics.Event.NOT_SENT)).isEqualTo(1);
        assertThat(audited()).extracting(AuditLog::getEventType).containsExactly(StaffLoanPayoutService.NOT_SENT);
    }

    @Test
    @DisplayName("a booking that could not be built never left: READY again, and a pass goes on")
    void unbuildableIsPassedOver() {
        answer = new PreApprovedBookingResult(0, null, null, new BookingNotBuiltException("Booking of loan"
                + " SGL-2026-000143 not sent: the request could not be built (amount)", null));

        Outcome outcome = service.payOut(143L);

        assertThat(outcome).isEqualTo(Outcome.PASSED_OVER);
        assertThat(outcome.carriesOn()).isTrue();
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
        assertThat(loan.getPayoutMessage()).contains("could not be built").contains("once what stopped it is put right");
    }

    @ParameterizedTest(name = "HTTP {0} is the bank's own refusal: REFUSED, the loan kept for a person")
    @ValueSource(ints = {400, 404, 405, 410, 413, 415, 418, 422, 451})
    void fourHundredsAreRefused(int status) {
        assertOutcome(status, Outcome.REFUSED, StaffLoanPayoutStatus.REFUSED);
        assertThat(loan.getPayoutMessage()).contains("HTTP " + status).contains("nothing was booked");
        assertThat(audited()).extracting(AuditLog::getEventType).containsExactly(StaffLoanPayoutService.REFUSED);
    }

    @ParameterizedTest(name = "HTTP {0} does not say whether it was booked: IN_DOUBT, never sent again")
    @ValueSource(ints = {301, 302, 303, 307, 308, 408, 409, 500, 502, 503, 504, 100})
    void ambiguousAnswersAreInDoubt(int status) {
        assertOutcome(status, Outcome.IN_DOUBT, StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(loan.getPayoutMessage()).contains("HTTP " + status).contains("never sent again");
        assertThat(audited()).extracting(AuditLog::getEventType).containsExactly(StaffLoanPayoutService.IN_DOUBT);
    }

    private void assertOutcome(int status, Outcome expected, StaffLoanPayoutStatus payout) {
        answer = new PreApprovedBookingResult(status, null, "{\"message\":\"x\"}", null);

        assertThat(service.payOut(143L)).isEqualTo(expected);

        assertThat(loan.getPayoutStatus()).isEqualTo(payout);
        assertThat(loan.getStatus()).as("no answer cancels the loan").isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(loan.getPayoutLastAnswer()).isEqualTo("{\"message\":\"x\"}");
        assertThat(counted(StaffLoanPayoutMetrics.Event.valueOf(expected.name()))).isEqualTo(1);
        verify(bank, times(1)).bookPreApproved(any());
    }

    @Test
    @DisplayName("a booking that provably never left (the login failed, the connection never opened) is READY again")
    void neverSentIsReleased() {
        answer = new PreApprovedBookingResult(0, null, null, new BookingNotSentException("Booking of loan SGL-2026-000143"
                + " not sent: InnBucks login failed (HTTP 503)", null));

        assertThat(service.payOut(143L)).isEqualTo(Outcome.NOT_SENT);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
        assertThat(loan.getPayoutClaimedAt()).isNull();
        assertThat(loan.getPayoutMessage()).startsWith("Not sent: BookingNotSentException").contains("login failed");
        assertThat(Outcome.NOT_SENT.carriesOn()).isFalse();
    }

    @Test
    @DisplayName("no answer read after the request may have left (a read timeout), or a call that broke, or returned"
            + " nothing, is IN_DOUBT")
    void noAnswerIsInDoubt() {
        answer = new PreApprovedBookingResult(0, null, null, new ResourceAccessException("Read timed out"));
        assertThat(service.payOut(143L)).isEqualTo(Outcome.IN_DOUBT);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(loan.getPayoutMessage()).contains("Read timed out");
        assertThat(loan.getPayoutLastAnswer()).isEqualTo("ResourceAccessException: Read timed out");

        loan = cashLoan(StaffLoanPayoutStatus.READY).build();
        when(bank.bookPreApproved(any())).thenThrow(new IllegalStateException("boom"));
        assertThat(service.payOut(143L)).isEqualTo(Outcome.IN_DOUBT);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);

        loan = cashLoan(StaffLoanPayoutStatus.READY).build();
        org.mockito.Mockito.reset(bank);
        assertThat(service.payOut(143L)).as("a null result").isEqualTo(Outcome.IN_DOUBT);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
    }

    @Test
    @DisplayName("a payout once claimed is never sent again: a second pay-out finds it settled and skips it")
    void sentOnce() {
        service.payOut(143L);

        assertThat(service.payOut(143L)).isEqualTo(Outcome.SKIPPED);

        verify(bank, times(1)).bookPreApproved(any());
    }

    @ParameterizedTest(name = "a payout {0} is not this caller's to send")
    @EnumSource(value = StaffLoanPayoutStatus.class, names = "READY", mode = EnumSource.Mode.EXCLUDE)
    void onlyReadyIsSent(StaffLoanPayoutStatus status) {
        StaffLoanStatus loanStatus = switch (status) {
            case PAID -> StaffLoanStatus.DISBURSED;
            case FAILED, WITHDRAWN -> StaffLoanStatus.CANCELLED;
            default -> StaffLoanStatus.AWAITING_DISBURSEMENT;
        };
        loan = cashLoan(status).status(loanStatus).build();

        assertThat(service.payOut(143L)).isEqualTo(Outcome.SKIPPED);

        verify(bank, never()).bookPreApproved(any());
        assertThat(loan.getPayoutStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("a voucher loan, a cancelled one, or one that is gone is never sent")
    void neverAVoucherLoan() {
        loan = StaffLoan.builder().id(143L).reference("SGL-2026-000143").staffMemberId(2L)
                .status(StaffLoanStatus.AWAITING_DISBURSEMENT).build();
        assertThat(service.payOut(143L)).isEqualTo(Outcome.SKIPPED);

        loan = cashLoan(StaffLoanPayoutStatus.READY).status(StaffLoanStatus.CANCELLED).build();
        assertThat(service.payOut(143L)).isEqualTo(Outcome.SKIPPED);

        loan = null;
        assertThat(service.payOut(143L)).isEqualTo(Outcome.SKIPPED);

        verify(bank, never()).bookPreApproved(any());
        assertThat(Outcome.SKIPPED.carriesOn()).isTrue();
    }

    @ParameterizedTest(name = "a borrower {0} on the register is not paid: HELD")
    @EnumSource(value = StaffEmploymentStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void heldWhenNotActive(StaffEmploymentStatus status) {
        member.setEmploymentStatus(status);

        assertHeld("is " + status + " on the staff register");
    }

    @Test
    @DisplayName("a borrower no longer on the register is not paid: HELD")
    void heldWhenNotOnTheRegister() {
        when(members.findById(2L)).thenReturn(Optional.empty());

        assertHeld("not on the staff register");
    }

    @Test
    @DisplayName("fewer than min-days-before-due (7) to the repayment date: HELD; seven days exactly: sent")
    void heldTooCloseToTheRepaymentDate() {
        loan = cashLoan(StaffLoanPayoutStatus.READY).dueDate(LocalDate.of(2026, 11, 4)).build();
        assertHeld("too close to the repayment date");

        loan = cashLoan(StaffLoanPayoutStatus.READY).dueDate(LocalDate.of(2026, 11, 5)).build();
        assertThat(service.payOut(143L)).isEqualTo(Outcome.BOOKED);
    }

    @Test
    @DisplayName("a wallet payout whose borrower's phone on the register has changed since acceptance: HELD")
    void heldWhenTheWalletChanged() {
        member.setMsisdn("263782606983");

        assertHeld("no longer the InnBucks wallet they accepted");
    }

    @Test
    @DisplayName("an account payout whose register account changed, or now holds a wallet: HELD; the same account: sent"
            + " to it")
    void heldWhenTheAccountChanged() {
        properties.getPayout().setLoanType("CONSUMER_FINANCE");
        loan = cashLoan(StaffLoanPayoutStatus.READY).payoutDestination(StaffLoanPayoutDestination.BR_ACCOUNT)
                .payoutAccount("1234567890").build();
        member.setWalletAccountNumber("1234567891");
        assertHeld("account number on the staff register is no longer");

        loan = cashLoan(StaffLoanPayoutStatus.READY).payoutDestination(StaffLoanPayoutDestination.BR_ACCOUNT)
                .payoutAccount("1234567890").build();
        member.setWalletAccountNumber("263772123123");
        assertHeld("account number on the staff register is no longer");

        member.setWalletAccountNumber("1234567890");
        assertThat(service.payOut(143L)).isEqualTo(Outcome.BOOKED);
        assertThat(sent().settlementAccount()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("a borrower the bank was found to have paid for a cancelled loan is not paid again: HELD")
    void heldAfterPaidAfterCancel() {
        when(loans.existsByStaffMemberIdAndPayoutPaidAfterCancelAtIsNotNull(2L)).thenReturn(true);

        assertHeld("confirmed never booked");
    }

    @Test
    @DisplayName("no product, or an account payout with no loan type: HELD, never sent half-made")
    void heldWithoutSettings() {
        properties.getPayout().setProduct("");
        assertHeld("loans.staff-loans.payout.product");

        properties.getPayout().setProduct("STAFFGROC");
        loan = cashLoan(StaffLoanPayoutStatus.READY).payoutDestination(StaffLoanPayoutDestination.BR_ACCOUNT)
                .payoutAccount("1234567890").build();
        member.setWalletAccountNumber("1234567890");
        assertHeld("loans.staff-loans.payout.loan-type");
    }

    private void assertHeld(String why) {
        org.mockito.Mockito.clearInvocations(audit);
        double heldBefore = counted(StaffLoanPayoutMetrics.Event.HELD);

        Outcome outcome = service.payOut(143L);

        assertThat(outcome).isEqualTo(Outcome.HELD);
        assertThat(outcome.carriesOn()).as("a held payout does not stop a pass").isTrue();
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
        assertThat(loan.getPayoutClaimedAt()).isNull();
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(loan.getPayoutMessage()).startsWith("Not sent: ").contains(why);
        verify(bank, never()).bookPreApproved(any());
        assertThat(counted(StaffLoanPayoutMetrics.Event.HELD)).isEqualTo(heldBefore + 1);
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutService.HELD);
            assertThat(entry.getDetail()).contains(why);
        });
    }

    @Test
    @DisplayName("held again for the same reason: counted, not audited again")
    void heldOnceAudited() {
        member.setEmploymentStatus(StaffEmploymentStatus.SUSPENDED);
        service.payOut(143L);
        service.payOut(143L);

        assertThat(counted(StaffLoanPayoutMetrics.Event.HELD)).isEqualTo(2);
        assertThat(audited()).hasSize(1);
        verify(loans, times(1)).save(any());
    }

    @Test
    @DisplayName("a 2xx that arrives after the stale-claim hold took the loan IN_DOUBT is still recorded: BOOKED and"
            + " seen by the bank, under the lock, so nobody can record it never booked")
    void aLate2xxIsBooked() {
        duringCall = () -> loan.payoutInDoubt("held as abandoned", null);

        assertThat(service.payOut(143L)).isEqualTo(Outcome.BOOKED);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getPayoutBankSeenAt()).isNotNull();
        assertThat(loan.getPayoutApplyAnswer()).isEqualTo("{\"responseCode\":\"000\"}");
        assertThat(loan.getPayoutMessage()).isNull();
        assertThat(counted(StaffLoanPayoutMetrics.Event.BOOKED)).isEqualTo(1);
        assertThat(counted(StaffLoanPayoutMetrics.Event.NOT_RECORDED)).isZero();
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutService.BOOKED);
            assertThat(entry.getDetail()).contains("late:true");
        });
    }

    @Test
    @DisplayName("any other answer that arrives after the stale-claim hold took the loan is not applied: NOT_RECORDED,"
            + " audited, and the loan stays IN_DOUBT for the inquiry")
    void claimSuperseded() {
        answer = new PreApprovedBookingResult(0, null, null, new ResourceAccessException("Read timed out"));
        duringCall = () -> loan.payoutInDoubt("held as abandoned", null);

        assertThat(service.payOut(143L)).isEqualTo(Outcome.NOT_RECORDED);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(loan.getPayoutMessage()).isEqualTo("held as abandoned");
        assertThat(loan.getPayoutBankSeenAt()).isNull();
        assertThat(counted(StaffLoanPayoutMetrics.Event.NOT_RECORDED)).isEqualTo(1);
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutService.NOT_RECORDED);
            assertThat(entry.getDetail()).contains("reason:claim_superseded").contains("outcome:IN_DOUBT");
        });
        assertThat(Outcome.NOT_RECORDED.carriesOn()).isFalse();
    }

    @Test
    @DisplayName("a 2xx that arrives after the loan was settled some other way is not applied: NOT_RECORDED, audited"
            + " as a 2xx")
    void a2xxAfterTheLoanMovedOnIsAudited() {
        duringCall = () -> loan = cashLoan(StaffLoanPayoutStatus.WITHDRAWN).status(StaffLoanStatus.CANCELLED).build();

        assertThat(service.payOut(143L)).isEqualTo(Outcome.NOT_RECORDED);

        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutService.BOOKED_NOT_RECORDED);
            assertThat(entry.getDetail()).contains("reason:claim_superseded").contains("http:201");
        });
    }

    @Test
    @DisplayName("a settle that cannot be saved leaves the claim standing (SENDING, never sent again): NOT_RECORDED")
    void settleFails() {
        answer = new PreApprovedBookingResult(503, null, null, null);
        failTheSettle();

        assertThat(service.payOut(143L)).isEqualTo(Outcome.NOT_RECORDED);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.SENDING);
        assertThat(loan.getPayoutClaimedAt()).isEqualTo(CLAIMED_AT);
        assertThat(transactions.rolledBack()).isEqualTo(1);
        verify(loans, never()).markPayoutSeenByBank(anyLong(), any(), any());
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutService.NOT_RECORDED);
            assertThat(entry.getDetail()).contains("reason:settle_failed").contains("error:IllegalStateException");
        });
    }

    @Test
    @DisplayName("a 2xx whose settle cannot be saved is still recorded as seen by the bank, in a transaction of its"
            + " own, and audited as a 2xx: nobody can record it never booked")
    void aTwoHundredWhoseSettleFailsIsStillSeen() {
        failTheSettle();

        assertThat(service.payOut(143L)).isEqualTo(Outcome.NOT_RECORDED);

        verify(loans).markPayoutSeenByBank(org.mockito.ArgumentMatchers.eq(143L), any(),
                org.mockito.ArgumentMatchers.eq("{\"responseCode\":\"000\"}"));
        assertThat(transactions.committed()).as("the claim, then the mark").isEqualTo(2);
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutService.BOOKED_NOT_RECORDED);
            assertThat(entry.getDetail()).contains("reason:settle_failed").contains("outcome:BOOKED")
                    .contains("http:201");
        });
    }

    private void failTheSettle() {
        AtomicReference<Integer> locks = new AtomicReference<>(0);
        when(loans.lockById(143L)).thenAnswer(i -> {
            locks.set(locks.get() + 1);
            if (locks.get() > 1) {
                throw new IllegalStateException("connection lost");
            }
            return Optional.of(loan);
        });
    }

    @Test
    @DisplayName("a claim that cannot be made sends nothing: NOT_SENT, and the pass stops")
    void claimFails() {
        when(members.lockRegister(anyLong())).thenThrow(new IllegalStateException("lock timeout"));

        assertThat(service.payOut(143L)).isEqualTo(Outcome.NOT_SENT);

        verify(bank, never()).bookPreApproved(any());
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
    }

    @Test
    @DisplayName("never called inside a transaction: the bank would be asked with a connection held")
    void neverInsideATransaction() {
        TransactionTemplate template = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> template.executeWithoutResult(status -> service.payOut(143L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("never sent inside a transaction");
        verify(bank, never()).bookPreApproved(any());
    }

    @Test
    @DisplayName("an audit that cannot be written never stops the payout")
    void auditFailureIsSwallowed() {
        org.mockito.Mockito.doThrow(new IllegalStateException("no connection")).when(audit).record(any());

        assertThat(service.payOut(143L)).isEqualTo(Outcome.BOOKED);
    }

    @Test
    @DisplayName("a wallet payout is booked for the wallet it was accepted for, with what the register holds, and no"
            + " settlement account")
    void walletBooking() {
        service.payOut(143L);

        PreApprovedLoanBooking booking = sent();
        assertThat(booking.participantReference()).isEqualTo("SGL-2026-000143");
        assertThat(booking.firstName()).isEqualTo("Chipo Rudo");
        assertThat(booking.lastName()).isEqualTo("Banda");
        assertThat(booking.idNumber()).isEqualTo("63-2345678B42");
        assertThat(booking.amount()).isEqualByComparingTo("150.00");
        assertThat(booking.currency()).isEqualTo("USD");
        assertThat(booking.product()).isEqualTo("STAFFGROC");
        assertThat(booking.loanType()).isEqualTo("PERSONAL");
        assertThat(booking.tenureInMonths()).isEqualTo(1);
        assertThat(booking.msisdn()).isEqualTo("263772123123");
        assertThat(booking.settlementAccount()).isNull();
        assertThat(booking.employer()).isEqualTo("InnBucks MicroBank");
        assertThat(booking.employerNumber()).isEqualTo("E1012");
        assertThat(booking.employmentStartDate()).isEqualTo(LocalDate.of(2015, 2, 2));
    }

    @Test
    @DisplayName("an account payout names the account to settle into and the borrower's phone; a blank employer is"
            + " left out")
    void accountBooking() {
        properties.getPayout().setLoanType(" CONSUMER_FINANCE ");
        properties.getPayout().setEmployerName(" ");
        loan = cashLoan(StaffLoanPayoutStatus.READY).payoutDestination(StaffLoanPayoutDestination.BR_ACCOUNT)
                .payoutAccount("1234567890").build();
        member.setWalletAccountNumber("1234567890");

        service.payOut(143L);

        PreApprovedLoanBooking booking = sent();
        assertThat(booking.settlementAccount()).isEqualTo("1234567890");
        assertThat(booking.msisdn()).isEqualTo("263772123123");
        assertThat(booking.loanType()).isEqualTo("CONSUMER_FINANCE");
        assertThat(booking.employer()).isNull();
    }

    @Test
    @DisplayName("names: the last word is the surname; one word is a surname alone; whitespace collapsed")
    void names() {
        assertThat(StaffLoanPayoutService.names("Chipo Banda")).containsExactly("Chipo", "Banda");
        assertThat(StaffLoanPayoutService.names("  Tendai   Rufaro  Moyo ")).containsExactly("Tendai Rufaro", "Moyo");
        assertThat(StaffLoanPayoutService.names("Madonna")).containsExactly(null, "Madonna");
        assertThat(StaffLoanPayoutService.names(" ")).containsExactly(null, null);
        assertThat(StaffLoanPayoutService.names(null)).containsExactly(null, null);
    }

    @Test
    @DisplayName("national ID: the register's 632345678B42 is sent as 63-2345678B42; anything else as stored")
    void idNumbers() {
        assertThat(StaffLoanPayoutService.idNumber("632345678B42")).isEqualTo("63-2345678B42");
        assertThat(StaffLoanPayoutService.idNumber("08123456F75")).isEqualTo("08-123456F75");
        assertThat(StaffLoanPayoutService.idNumber("AB1234567")).isEqualTo("AB1234567");
        assertThat(StaffLoanPayoutService.idNumber("63234567842")).isEqualTo("63234567842");
        assertThat(StaffLoanPayoutService.idNumber(null)).isNull();
    }

    @Test
    @DisplayName("a pass carries on after BOOKED, PASSED_OVER, HELD or SKIPPED, and stops at anything that may be the"
            + " bank's trouble")
    void carriesOn() {
        assertThat(java.util.Arrays.stream(Outcome.values()).filter(Outcome::carriesOn))
                .containsExactly(Outcome.BOOKED, Outcome.PASSED_OVER, Outcome.HELD, Outcome.SKIPPED);
    }

    @Test
    @DisplayName("every outcome counter is registered at 0")
    void countersStartAtZero() {
        for (StaffLoanPayoutMetrics.Event event : StaffLoanPayoutMetrics.Event.values()) {
            assertThat(registry.get(StaffLoanPayoutMetrics.OUTCOMES).tag("outcome", event.tag()).counter().count())
                    .as(event.tag()).isZero();
        }
        assertThat(registry.get(StaffLoanPayoutMetrics.OUTCOMES).counters()).hasSize(18);
    }
}
