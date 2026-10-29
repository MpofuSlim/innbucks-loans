package zw.co.innbucks.loans.core.staff.loan;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.InnbucksLoanInquiry;
import zw.co.innbucks.loans.core.disbursements.InquiryAnswer;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatusService.Checked;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationService;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationTemplate;
import zw.co.innbucks.loans.core.user.User;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Following a cash payout up with the bank: a stale claim held IN_DOUBT; the inquiry asked with no transaction open
 * and settled under the loan's row lock; "paid" recorded only when the bank's answer names this loan's reference,
 * amount and account; "not found" recorded once and never failing a payout; an answer that says nothing changing
 * nothing; a cancelled payout watched; and a person's "paid" and "never booked", each refused to a party
 * to the loan. The clock stands at 29 October 2026, 10:00 in Harare.
 */
class StaffLoanPayoutStatusServiceTest {

    private static final Instant INSTANT = Instant.parse("2026-10-29T08:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 8, 0);
    private static final String REFERENCE = "SGL-2026-000143";

    private final StaffLoanRepository loans = mock(StaffLoanRepository.class);
    private final StaffMemberRepository members = mock(StaffMemberRepository.class);
    private final StaffLoanService loanService = mock(StaffLoanService.class);
    private final StaffNotificationService notifications = mock(StaffNotificationService.class);
    private final InnbucksLoanInquiry inquiry = mock(InnbucksLoanInquiry.class);
    private final AuditService audit = mock(AuditService.class);
    private final StaffLoanPayoutMetrics metrics = new StaffLoanPayoutMetrics(new SimpleMeterRegistry());
    private final CountingTransactionManager transactions = new CountingTransactionManager();
    private final StaffLoanProperties properties = new StaffLoanProperties();
    private final StaffLoanPayoutStatusService service = new StaffLoanPayoutStatusService(loans, members, loanService,
            notifications, inquiry, properties, metrics, audit,
            new MarketTimeZone("ZW", Clock.fixed(INSTANT, ZoneOffset.UTC)), transactions);

    private StaffLoan loan = cashLoan(StaffLoanPayoutStatus.BOOKED).build();
    private final StaffMember member = StaffMember.builder().id(2L).employeeNumber("E1012").fullName("Chipo Banda")
            .nationalId("632345678B42").msisdn("263772123123").grade("C4")
            .employmentStatus(StaffEmploymentStatus.ACTIVE).engagementDate(LocalDate.of(2015, 2, 2))
            .walletAccountNumber("263772123123").updatedBatchId(17L).build();
    private final List<Boolean> transactionOpenDuringInquiry = new ArrayList<>();
    private InquiryAnswer answer = found("SETTLED");

    StaffLoanPayoutStatusServiceTest() {
        properties.getPayout().setMethod(StaffLoanPayoutMethod.CASH);
        properties.getPayout().setDestination(StaffLoanPayoutDestination.VEENGU_WALLET);
        properties.getPayout().setProduct("STAFFGROC");
        when(loans.findById(143L)).thenAnswer(i -> Optional.ofNullable(loan));
        when(loans.lockById(143L)).thenAnswer(i -> Optional.ofNullable(loan));
        when(loans.save(any())).thenAnswer(i -> i.getArgument(0));
        when(members.findById(2L)).thenReturn(Optional.of(member));
        when(inquiry.inquire(REFERENCE)).thenAnswer(i -> {
            transactionOpenDuringInquiry.add(TransactionSynchronizationManager.isActualTransactionActive());
            return answer;
        });
    }

    private static StaffLoan.StaffLoanBuilder cashLoan(StaffLoanPayoutStatus payout) {
        StaffLoan.StaffLoanBuilder builder = StaffLoan.builder().id(143L).reference(REFERENCE).staffMemberId(2L)
                .offerId(31L).employeeNumber("E1012").fullName("Chipo Banda").msisdn("263772123123").grade("C4")
                .amount(new BigDecimal("150.00")).currency("USD").interestRate(BigDecimal.ZERO)
                .totalRepayable(new BigDecimal("150.00")).dueDate(LocalDate.of(2026, 11, 20))
                .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS)
                .status(StaffLoanStatus.AWAITING_DISBURSEMENT).acceptedAt(LocalDateTime.of(2026, 10, 29, 7, 0))
                .payoutMethod(StaffLoanPayoutMethod.CASH).payoutDestination(StaffLoanPayoutDestination.VEENGU_WALLET)
                .payoutAccount("263772123123").payoutStatus(payout);
        if (payout == StaffLoanPayoutStatus.SENDING) {
            builder.payoutClaimedAt(NOW.minusMinutes(31));
        }
        if (payout == StaffLoanPayoutStatus.BOOKED) {
            builder.payoutBankSeenAt(NOW.minusMinutes(20)).payoutLastAnswer("{\"responseCode\":\"000\"}");
        }
        return builder;
    }

    private static InquiryAnswer found(String status) {
        return new InquiryAnswer(InquiryAnswer.Kind.FOUND, "000", status, "LN-0001", REFERENCE,
                new BigDecimal("15000"), BigDecimal.ZERO, "263772123123", null, "{\"responseCode\":\"000\"}", 200);
    }

    /** A FOUND answer that echoes only these. */
    private static InquiryAnswer echoing(String reference, BigDecimal amount, String msisdn, String settlementAccount) {
        return new InquiryAnswer(InquiryAnswer.Kind.FOUND, "000", "SETTLED", null, reference, amount, null, msisdn,
                settlementAccount, "{}", 200);
    }

    private static InquiryAnswer notFound() {
        return new InquiryAnswer(InquiryAnswer.Kind.NOT_FOUND, "004", null, null, null, null, null, null, null,
                "{\"responseCode\":\"004\"}", 200);
    }

    private List<AuditLog> audited() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit, org.mockito.Mockito.atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    private List<String> auditedEvents() {
        return audited().stream().map(AuditLog::getEventType).toList();
    }

    private double counted(StaffLoanPayoutMetrics.Event event) {
        return metrics.counted(event);
    }

    private static User user(String username, String idNumber, String mobile) {
        User user = new User();
        user.setUsername(username);
        user.setIdNumber(idNumber);
        user.setMobileNumber(mobile);
        return user;
    }

    private static final User FINANCE = user("finance1", "63-1111111-A-11", "0771000000");

    // --- abandoned claims ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a claim older than stale-claim-minutes (30) is held IN_DOUBT, never sent again; audited and counted")
    void staleClaimHeld() {
        loan = cashLoan(StaffLoanPayoutStatus.SENDING).payoutLastAnswer("an earlier refusal").build();

        assertThat(service.holdIfAbandoned(143L, NOW)).isTrue();

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(loan.getPayoutClaimedAt()).isNull();
        assertThat(loan.getPayoutMessage()).contains("never recorded").contains("never sent again");
        assertThat(loan.getPayoutLastAnswer()).isEqualTo("an earlier refusal");
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(counted(StaffLoanPayoutMetrics.Event.ABANDONED)).isEqualTo(1);
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutStatusService.CLAIM_ABANDONED);
            assertThat(entry.getDetail()).isEqualTo("reference:" + REFERENCE + ";claimedAt:" + NOW.minusMinutes(31)
                    + ";payout:IN_DOUBT");
        });
    }

    @Test
    @DisplayName("a stale claim whose 2xx was recorded as seen by the bank (its settle failed) is BOOKED, not IN_DOUBT")
    void staleClaimSeenByTheBankIsBooked() {
        loan = cashLoan(StaffLoanPayoutStatus.SENDING).payoutBankSeenAt(NOW.minusMinutes(30))
                .payoutApplyAnswer("{\"responseCode\":\"000\"}").build();

        assertThat(service.holdIfAbandoned(143L, NOW)).isTrue();

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getPayoutClaimedAt()).isNull();
        assertThat(loan.getPayoutLastAnswer()).isEqualTo("{\"responseCode\":\"000\"}");
        assertThat(loan.getPayoutMessage()).contains("taken by the bank").contains("never settled");
        assertThat(audited()).singleElement().extracting(AuditLog::getDetail).asString().endsWith(";payout:BOOKED");
    }

    @Test
    @DisplayName("a fresh claim, or a payout no longer SENDING, is left alone")
    void freshClaimLeftAlone() {
        loan = cashLoan(StaffLoanPayoutStatus.SENDING).payoutClaimedAt(NOW.minusMinutes(29)).build();
        assertThat(service.holdIfAbandoned(143L, NOW)).isFalse();
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.SENDING);

        loan = cashLoan(StaffLoanPayoutStatus.BOOKED).build();
        assertThat(service.holdIfAbandoned(143L, NOW)).isFalse();
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);

        verify(loans, never()).save(any());
        assertThat(counted(StaffLoanPayoutMetrics.Event.ABANDONED)).isZero();
    }

    @Test
    @DisplayName("holding abandoned claims walks the stale ones in chunks, from the cutoff, each in its own transaction")
    void holdAbandonedClaims() {
        loan = cashLoan(StaffLoanPayoutStatus.SENDING).build();
        when(loans.findPayoutClaimedBeforeIds(eq(NOW.minusMinutes(30)), anyLong(), any(Pageable.class)))
                .thenAnswer(i -> (long) i.getArgument(1) < 143L ? List.of(143L) : List.of());

        assertThat(service.holdAbandonedClaims(NOW)).isEqualTo(1);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(transactions.committed()).isEqualTo(1);
    }

    // --- the inquiry --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("SETTLED, naming this loan: PAID, the loan DISBURSED under its own reference with the bank's loan"
            + " account, the borrower told; asked with no transaction open")
    void paid() {
        Checked checked = service.check(143L);

        assertThat(checked).isEqualTo(Checked.PAID);
        assertThat(transactionOpenDuringInquiry).containsExactly(false);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.PAID);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(loan.getDisbursedAt()).isEqualTo(NOW);
        assertThat(loan.getDisbursementReference()).isEqualTo(REFERENCE);
        assertThat(loan.getBankLoanAccount()).isEqualTo("LN-0001");
        assertThat(loan.getPayoutBankStatus()).isEqualTo("SETTLED");
        verify(notifications).notifyLoan(loan, StaffNotificationTemplate.LOAN_PAID_OUT, "InnBucks paid your Staff"
                + " Grocery Loan of USD 150.00 into your InnBucks wallet ending 3123. Repaid from your salary on 20 Nov"
                + " 2026. Ref SGL-2026-000143");
        assertThat(counted(StaffLoanPayoutMetrics.Event.PAID)).isEqualTo(1);
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutStatusService.PAID_OUT);
            assertThat(entry.getActorId()).isEqualTo(StaffLoanPayoutStatusService.SYSTEM_ACTOR);
            assertThat(entry.getChannelUsed()).isEqualTo("system");
            assertThat(entry.getDetail()).isEqualTo("reference:" + REFERENCE + ";amount:150.00;currency:USD;"
                    + "destination:VEENGU_WALLET;account:****3123;bankLoanAccount:****0001;bankStatus:SETTLED;"
                    + "via:inquiry");
        });
        verify(loanService, never()).flagEmploymentAtPayout(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a paid payout is not asked about again, and its borrower is never told twice")
    void paidOnce() {
        service.check(143L);

        assertThat(service.check(143L)).isEqualTo(Checked.SKIPPED);

        verify(inquiry, times(1)).inquire(REFERENCE);
        verify(notifications, times(1)).notifyLoan(any(), any(), anyString());
    }

    @Test
    @DisplayName("a FOUND answer takes the register lock before the loan's row lock")
    void registerBeforeLoan() {
        service.check(143L);

        InOrder order = inOrder(members, loans);
        order.verify(members).lockRegister(StaffRegisterService.REGISTER_LOCK);
        order.verify(loans).lockById(143L);
    }

    @Test
    @DisplayName("an IN_DOUBT payout the bank answers SETTLED is PAID too, the bank's case of the status ignored")
    void inDoubtPaid() {
        loan = cashLoan(StaffLoanPayoutStatus.IN_DOUBT).build();
        answer = found("settled");

        assertThat(service.check(143L, "finance1")).isEqualTo(Checked.PAID);

        assertThat(loan.getPayoutBankSeenAt()).isEqualTo(NOW);
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getActorId()).isEqualTo("finance1");
            assertThat(entry.getChannelUsed()).isEqualTo("admin-portal");
        });
    }

    @Test
    @DisplayName("the amount echoed in major units is the loan's too; no echo at all is no mismatch")
    void amountInMajorUnits() {
        answer = echoing(null, new BigDecimal("150"), null, null);

        assertThat(service.check(143L)).isEqualTo(Checked.PAID);
        assertThat(loan.getBankLoanAccount()).isNull();
    }

    @Test
    @DisplayName("a wallet echoed however the bank writes a phone is the same wallet")
    void walletEchoedInAnotherShape() {
        answer = echoing(REFERENCE, null, "0772123123", null);

        assertThat(service.check(143L)).isEqualTo(Checked.PAID);
    }

    @Test
    @DisplayName("an account payout's answer that echoes the borrower's phone and no account is PAID: the phone is"
            + " checked against the borrower's, never against the account")
    void accountPayoutEchoingThePhone() {
        loan = cashLoan(StaffLoanPayoutStatus.BOOKED).payoutDestination(StaffLoanPayoutDestination.BR_ACCOUNT)
                .payoutAccount("1234567890").build();
        answer = echoing(REFERENCE, new BigDecimal("15000"), "263772123123", null);

        assertThat(service.check(143L)).isEqualTo(Checked.PAID);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
    }

    @Test
    @DisplayName("PENDING-like statuses: an IN_DOUBT payout is BOOKED after all, seen by the bank; a BOOKED one stays")
    void pending() {
        loan = cashLoan(StaffLoanPayoutStatus.IN_DOUBT).payoutMessage("held for the inquiry").build();
        answer = found("PROCESSING");

        assertThat(service.check(143L)).isEqualTo(Checked.BOOKED);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getPayoutBankSeenAt()).isEqualTo(NOW);
        assertThat(loan.getPayoutBankStatus()).isEqualTo("PROCESSING");
        assertThat(loan.getPayoutMessage()).isNull();
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);

        assertThat(service.check(143L)).isEqualTo(Checked.BOOKED);
        verifyNoInteractions(notifications);
        assertThat(audited()).isEmpty();
    }

    @Test
    @DisplayName("a status the service does not know: held BOOKED, not paid; audited once, counted each time")
    void unknownStatus() {
        loan = cashLoan(StaffLoanPayoutStatus.IN_DOUBT).build();
        answer = found("REVERSED");

        assertThat(service.check(143L)).isEqualTo(Checked.UNKNOWN_STATUS);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getPayoutMessage()).contains("REVERSED");
        when(audit.hasRecorded(StaffLoanPayoutStatusService.UNKNOWN_BANK_STATUS, "STAFF_LOAN", "143"))
                .thenReturn(true);
        assertThat(service.check(143L)).isEqualTo(Checked.UNKNOWN_STATUS);

        assertThat(auditedEvents()).containsExactly(StaffLoanPayoutStatusService.UNKNOWN_BANK_STATUS);
        assertThat(counted(StaffLoanPayoutMetrics.Event.UNKNOWN_BANK_STATUS)).isEqualTo(2);
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("FOUND with no status at all is not paid either")
    void noStatus() {
        answer = found(null);

        assertThat(service.check(143L)).isEqualTo(Checked.UNKNOWN_STATUS);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
    }

    @Test
    @DisplayName("SETTLED naming another reference, amount or account is not recorded as paid: BOOKED, audited once")
    void echoMismatch() {
        loan = cashLoan(StaffLoanPayoutStatus.IN_DOUBT).build();
        answer = new InquiryAnswer(InquiryAnswer.Kind.FOUND, "000", "SETTLED", "LN-0001", "SGL-2026-000144",
                new BigDecimal("1500"), BigDecimal.ZERO, "263782606983", null, "{}", 200);

        assertThat(service.check(143L)).isEqualTo(Checked.ECHO_MISMATCH);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(loan.getPayoutMessage()).contains("reference and amount and account");
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutStatusService.ECHO_MISMATCH);
            assertThat(entry.getDetail()).contains("mismatch:reference,amount,account");
        });
        assertThat(counted(StaffLoanPayoutMetrics.Event.ECHO_MISMATCH)).isEqualTo(1);
        verifyNoInteractions(notifications);

        when(audit.hasRecorded(StaffLoanPayoutStatusService.ECHO_MISMATCH, "STAFF_LOAN", "143")).thenReturn(true);
        assertThat(service.check(143L)).isEqualTo(Checked.ECHO_MISMATCH);
        assertThat(audited()).hasSize(1);
    }

    @Test
    @DisplayName("each echo is checked on its own, and only when the answer carries it")
    void eachEcho() {
        StaffLoan base = cashLoan(StaffLoanPayoutStatus.BOOKED).build();
        assertThat(StaffLoanPayoutStatusService.echoMismatches(base, echoing("SGL-2026-000999", null, null, null)))
                .containsExactly("reference");
        assertThat(StaffLoanPayoutStatusService.echoMismatches(base, echoing(null, new BigDecimal("149.99"), null,
                null))).containsExactly("amount");
        assertThat(StaffLoanPayoutStatusService.echoMismatches(base, echoing(null, new BigDecimal("15000.00"), null,
                null))).isEmpty();
        assertThat(StaffLoanPayoutStatusService.echoMismatches(base, echoing(null, null, null, null))).isEmpty();
    }

    @Test
    @DisplayName("where it paid is read by the destination: a wallet by the echoed phone (a settlementAccount is not"
            + " read), an account by the echoed settlementAccount, its phone by the borrower's")
    void accountEchoesByDestination() {
        StaffLoan wallet = cashLoan(StaffLoanPayoutStatus.BOOKED).build();
        assertThat(StaffLoanPayoutStatusService.echoMismatches(wallet, echoing(null, null, "+263 772 123 123", null)))
                .isEmpty();
        assertThat(StaffLoanPayoutStatusService.echoMismatches(wallet, echoing(null, null, "263782606983", null)))
                .containsExactly("account");
        assertThat(StaffLoanPayoutStatusService.echoMismatches(wallet, echoing(null, null, "263772123123",
                "WALLET-77001"))).as("the wallet's own internal account is not the payout's").isEmpty();

        StaffLoan account = cashLoan(StaffLoanPayoutStatus.BOOKED)
                .payoutDestination(StaffLoanPayoutDestination.BR_ACCOUNT).payoutAccount("1234567890").build();
        assertThat(StaffLoanPayoutStatusService.echoMismatches(account, echoing(null, null, null, "12-3456-7890")))
                .isEmpty();
        assertThat(StaffLoanPayoutStatusService.echoMismatches(account, echoing(null, null, null, "001234567890")))
                .containsExactly("account");
        assertThat(StaffLoanPayoutStatusService.echoMismatches(account, echoing(null, null, "263772123123", null)))
                .as("the borrower's phone, echoed and no account: nothing to compare the account with").isEmpty();
        assertThat(StaffLoanPayoutStatusService.echoMismatches(account, echoing(null, null, "263782606983",
                "1234567890"))).containsExactly("phone");
    }

    @Test
    @DisplayName("paid with interest the loan does not carry: PAID anyway, audited and counted")
    void interestCharged() {
        answer = new InquiryAnswer(InquiryAnswer.Kind.FOUND, "000", "SETTLED", null, REFERENCE, null,
                new BigDecimal("3.5"), null, null, "{}", 200);

        assertThat(service.check(143L)).isEqualTo(Checked.PAID);

        assertThat(auditedEvents()).containsExactly(StaffLoanPayoutStatusService.PAID_OUT,
                StaffLoanPayoutStatusService.INTEREST_CHARGED);
        assertThat(counted(StaffLoanPayoutMetrics.Event.INTEREST_CHARGED)).isEqualTo(1);
    }

    @ParameterizedTest(name = "a status that says the bank declined it ({0}): BOOKED, dated once, audited once, for a"
            + " person; never paid")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"REJECTED", "cancelled", "FAILED"})
    void declined(String status) {
        loan = cashLoan(StaffLoanPayoutStatus.IN_DOUBT).build();
        answer = found(status);

        assertThat(service.check(143L)).isEqualTo(Checked.DECLINED);
        assertThat(service.check(143L)).isEqualTo(Checked.DECLINED);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(loan.getPayoutDeclinedAt()).isEqualTo(NOW);
        assertThat(loan.getPayoutCheckedAt()).isEqualTo(NOW);
        assertThat(loan.getPayoutBankStatus()).isEqualTo(status);
        assertThat(loan.getPayoutMessage()).contains("holds this payout as " + status)
                .contains("from 2026-10-30T10:00:00+02:00 a SUPER_ADMIN may record it as not booked");
        assertThat(auditedEvents()).containsExactly(StaffLoanPayoutStatusService.DECLINED);
        assertThat(counted(StaffLoanPayoutMetrics.Event.DECLINED)).isEqualTo(1);
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("NOT_FOUND: stamped and audited once, never failing the payout; FOUND later clears it, audited")
    void notFoundThenFound() {
        loan = cashLoan(StaffLoanPayoutStatus.IN_DOUBT).build();
        answer = notFound();

        assertThat(service.check(143L)).isEqualTo(Checked.NOT_FOUND);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(loan.getPayoutNotFoundAt()).isEqualTo(NOW);
        assertThat(loan.getPayoutMessage()).contains("may never have been booked")
                .contains("2026-10-30T10:00:00+02:00");
        assertThat(loan.getPayoutLastAnswer()).isEqualTo("{\"responseCode\":\"004\"}");

        assertThat(service.check(143L)).isEqualTo(Checked.NOT_FOUND);
        assertThat(loan.getPayoutNotFoundAt()).isEqualTo(NOW);
        assertThat(auditedEvents()).containsExactly(StaffLoanPayoutStatusService.NOT_FOUND);
        assertThat(counted(StaffLoanPayoutMetrics.Event.NOT_FOUND)).isEqualTo(1);
        verify(members, never()).lockRegister(anyLong());

        answer = found("PROCESSING");
        assertThat(service.check(143L)).isEqualTo(Checked.BOOKED);
        assertThat(loan.getPayoutNotFoundAt()).isNull();
        assertThat(auditedEvents()).containsExactly(StaffLoanPayoutStatusService.NOT_FOUND,
                StaffLoanPayoutStatusService.FOUND);
    }

    @Test
    @DisplayName("a BOOKED payout the bank now reports missing is noted for a person, and stays BOOKED")
    void bookedNotFound() {
        answer = notFound();

        assertThat(service.check(143L)).isEqualTo(Checked.NOT_FOUND);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getPayoutMessage()).contains("The bank took this payout, yet");
    }

    @Test
    @DisplayName("no answer (the bank, or the way to it, is down) changes nothing, and stops a run")
    void unanswered() {
        loan = cashLoan(StaffLoanPayoutStatus.IN_DOUBT).payoutMessage("held").build();
        for (InquiryAnswer down : List.of(InquiryAnswer.unanswered(0, null, "InnBucks login failed"),
                InquiryAnswer.unanswered(502, null, "Bad Gateway"), InquiryAnswer.unanswered(401, null, null))) {
            answer = down;

            Checked checked = service.check(143L);

            assertThat(checked).as(down.toString()).isEqualTo(Checked.UNANSWERED);
            assertThat(checked.carriesOn()).isFalse();
        }
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(loan.getPayoutMessage()).isEqualTo("held");
        verify(loans, never()).lockById(anyLong());
        assertThat(counted(StaffLoanPayoutMetrics.Event.INQUIRY_UNANSWERED)).isEqualTo(3);
    }

    @Test
    @DisplayName("an answer about this loan that says nothing (a bare 404, an HTML page, an unknown code) changes"
            + " nothing, and a run goes on to the next loan")
    void unreadable() {
        loan = cashLoan(StaffLoanPayoutStatus.IN_DOUBT).payoutMessage("held").build();
        for (InquiryAnswer nothing : List.of(InquiryAnswer.unanswered(404, null, null),
                InquiryAnswer.unanswered(200, null, "<html>Maintenance</html>"),
                InquiryAnswer.unanswered(200, "999", "{\"responseCode\":\"999\"}"))) {
            answer = nothing;

            Checked checked = service.check(143L);

            assertThat(checked).as(nothing.toString()).isEqualTo(Checked.UNREADABLE);
            assertThat(checked.carriesOn()).isTrue();
        }
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(loan.getPayoutMessage()).isEqualTo("held");
        assertThat(loan.getPayoutCheckedAt()).as("not an answer to go on").isNull();
        verify(loans, never()).lockById(anyLong());
        assertThat(counted(StaffLoanPayoutMetrics.Event.INQUIRY_UNANSWERED)).isEqualTo(3);
    }

    @Test
    @DisplayName("an inquiry that breaks, or returns nothing, is an ERROR and stops a run; a loan that cannot be read is"
            + " NOT_RECORDED and does not")
    void inquiryError() {
        when(inquiry.inquire(REFERENCE)).thenThrow(new IllegalStateException("boom"));

        Checked checked = service.check(143L);

        assertThat(checked).isEqualTo(Checked.ERROR);
        assertThat(checked.carriesOn()).isFalse();
        assertThat(counted(StaffLoanPayoutMetrics.Event.INQUIRY_ERROR)).isEqualTo(1);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);

        org.mockito.Mockito.reset(inquiry);
        assertThat(service.check(143L)).as("no answer object at all").isEqualTo(Checked.ERROR);
        assertThat(counted(StaffLoanPayoutMetrics.Event.INQUIRY_ERROR)).isEqualTo(2);

        when(loans.findById(143L)).thenThrow(new IllegalStateException("db"));
        Checked unread = service.check(143L);
        assertThat(unread).isEqualTo(Checked.NOT_RECORDED);
        assertThat(unread.carriesOn()).isTrue();
        assertThat(counted(StaffLoanPayoutMetrics.Event.NOT_RECORDED)).isEqualTo(1);
    }

    @Test
    @DisplayName("an answer that cannot be saved: NOT_RECORDED, counted, the borrower not told; a run goes on")
    void settleFails() {
        when(loans.save(any())).thenThrow(new IllegalStateException("connection lost"));

        Checked checked = service.check(143L);
        assertThat(checked).isEqualTo(Checked.NOT_RECORDED);
        assertThat(checked.carriesOn()).isTrue();

        assertThat(counted(StaffLoanPayoutMetrics.Event.NOT_RECORDED)).isEqualTo(1);
        assertThat(counted(StaffLoanPayoutMetrics.Event.PAID)).isZero();
        assertThat(audited()).isEmpty();
        assertThat(transactions.rolledBack()).isEqualTo(1);
    }

    @ParameterizedTest(name = "a payout {0} is not asked about")
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"READY", "SENDING", "REFUSED", "WITHDRAWN"})
    void notAskedAbout(StaffLoanPayoutStatus status) {
        loan = cashLoan(status).status(status == StaffLoanPayoutStatus.WITHDRAWN ? StaffLoanStatus.CANCELLED
                : StaffLoanStatus.AWAITING_DISBURSEMENT).build();

        assertThat(service.check(143L)).isEqualTo(Checked.SKIPPED);

        verifyNoInteractions(inquiry);
    }

    @Test
    @DisplayName("a voucher loan, or one that is gone, is never asked about")
    void voucherNotAskedAbout() {
        loan = StaffLoan.builder().id(143L).reference(REFERENCE).status(StaffLoanStatus.AWAITING_DISBURSEMENT).build();
        assertThat(service.check(143L)).isEqualTo(Checked.SKIPPED);
        loan = null;
        assertThat(service.check(143L)).isEqualTo(Checked.SKIPPED);
        verifyNoInteractions(inquiry);
    }

    @Test
    @DisplayName("settled by someone else between the read and the lock: nothing recorded")
    void settledMeanwhile() {
        StaffLoan paidMeanwhile = cashLoan(StaffLoanPayoutStatus.PAID).status(StaffLoanStatus.DISBURSED)
                .disbursedAt(NOW).disbursementReference(REFERENCE).build();
        when(loans.lockById(143L)).thenReturn(Optional.of(paidMeanwhile));

        assertThat(service.check(143L)).isEqualTo(Checked.SKIPPED);

        verify(loans, never()).save(any());
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("never asked inside a transaction")
    void neverInsideATransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> service.check(143L)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(inquiry);
    }

    // --- a person's "check now" ---------------------------------------------------------------------------------

    @Test
    @DisplayName("check now: asks the bank as the job would, and records it in the person's name")
    void checkNow() {
        assertThat(service.checkNow(143L, "finance1")).isEqualTo(Checked.PAID);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.PAID);
        assertThat(transactionOpenDuringInquiry).containsExactly(false);
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutStatusService.PAID_OUT);
            assertThat(entry.getActorId()).isEqualTo("finance1");
            assertThat(entry.getChannelUsed()).isEqualTo("admin-portal");
        });
    }

    @Test
    @DisplayName("check now on a stale claim: held IN_DOUBT first, then asked; never sent again")
    void checkNowHoldsAStaleClaim() {
        loan = cashLoan(StaffLoanPayoutStatus.SENDING).build();
        answer = found("PROCESSING");

        assertThat(service.checkNow(143L, "finance1")).isEqualTo(Checked.BOOKED);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(auditedEvents()).containsExactly(StaffLoanPayoutStatusService.CLAIM_ABANDONED);
        assertThat(counted(StaffLoanPayoutMetrics.Event.ABANDONED)).isEqualTo(1);
    }

    @Test
    @DisplayName("check now on a claim still being sent: 409, saying from when; the bank is not asked")
    void checkNowWhileSending() {
        loan = cashLoan(StaffLoanPayoutStatus.SENDING).payoutClaimedAt(NOW.minusMinutes(5)).build();

        assertThatThrownBy(() -> service.checkNow(143L, "finance1"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan " + REFERENCE + " is being sent to the bank right now (since"
                        + " 2026-10-29T09:55:00+02:00); its status can be checked once that send is settled, or from"
                        + " 2026-10-29T10:25:00+02:00 if it never is");

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.SENDING);
        verifyNoInteractions(inquiry);
    }

    @ParameterizedTest(name = "check now on a payout {0}: 409, the bank not asked")
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"READY", "REFUSED", "WITHDRAWN"})
    void checkNowRefused(StaffLoanPayoutStatus status) {
        loan = cashLoan(status).status(status == StaffLoanPayoutStatus.WITHDRAWN ? StaffLoanStatus.CANCELLED
                : StaffLoanStatus.AWAITING_DISBURSEMENT).build();

        assertThatThrownBy(() -> service.checkNow(143L, "finance1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("has payout " + status)
                .hasMessageContaining("only a payout the bank took (BOOKED) or may have taken (IN_DOUBT), or one"
                        + " recorded as never booked in the last 14 days, can be checked");
        verifyNoInteractions(inquiry);
    }

    @Test
    @DisplayName("check now on a voucher loan is 409, on no loan 404")
    void checkNowVoucherOrMissing() {
        loan = StaffLoan.builder().id(143L).reference(REFERENCE).status(StaffLoanStatus.AWAITING_DISBURSEMENT).build();
        assertThatThrownBy(() -> service.checkNow(143L, "finance1"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan " + REFERENCE + " is a voucher loan: it has no payout for the bank to report"
                        + " on");
        loan = null;
        assertThatThrownBy(() -> service.checkNow(143L, "finance1"))
                .isInstanceOf(NotFoundException.class).hasMessage("Staff loan 143 not found");
        verifyNoInteractions(inquiry);
    }

    @Test
    @DisplayName("check now on a payout confirmed never booked and still watched asks the bank")
    void checkNowWatched() {
        loan = failed(NOW.minusDays(3));
        answer = notFound();

        assertThat(service.checkNow(143L, "admin")).isEqualTo(Checked.NOT_FOUND);
    }

    @Test
    @DisplayName("check now with no answer to go on: a 502's exception; nothing changed")
    void checkNowUnanswered() {
        loan = cashLoan(StaffLoanPayoutStatus.IN_DOUBT).payoutMessage("held").build();
        answer = InquiryAnswer.unanswered(502, null, "Bad Gateway");

        assertThatThrownBy(() -> service.checkNow(143L, "finance1"))
                .isInstanceOf(StaffLoanPayoutUnansweredException.class)
                .hasMessage("The bank's inquiry about staff loan " + REFERENCE + " gave no answer to go on"
                        + " (UNANSWERED); nothing changed. Try again later.");
        assertThat(loan.getPayoutMessage()).isEqualTo("held");

        answer = InquiryAnswer.unanswered(404, null, null);
        assertThatThrownBy(() -> service.checkNow(143L, "finance1"))
                .isInstanceOf(StaffLoanPayoutUnansweredException.class).hasMessageContaining("(UNREADABLE)");

        when(inquiry.inquire(REFERENCE)).thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(() -> service.checkNow(143L, "finance1"))
                .isInstanceOf(StaffLoanPayoutUnansweredException.class).hasMessageContaining("(ERROR)");
    }

    @Test
    @DisplayName("check now whose answer cannot be saved: ours to fix, not the bank's (a 503's exception); nothing"
            + " changed")
    void checkNowNotRecorded() {
        when(loans.save(any())).thenThrow(new IllegalStateException("lock timeout"));

        assertThatThrownBy(() -> service.checkNow(143L, "finance1"))
                .isInstanceOf(StaffLoanPayoutNotRecordedException.class)
                .hasMessage("What the bank's inquiry said about staff loan " + REFERENCE + " could not be saved;"
                        + " nothing changed. Try again in a minute.");
        assertThat(transactions.rolledBack()).isEqualTo(1);
        assertThat(audited()).isEmpty();
    }

    @Test
    @DisplayName("check now is never made inside a transaction")
    void checkNowNeverInsideATransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> service.checkNow(143L, "finance1")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(inquiry);
    }

    // --- the employment flag at PAID ---------------------------------------------------------------------------

    @ParameterizedTest(name = "paid to a borrower now {0}: flagged, with the batch that moved them")
    @EnumSource(value = StaffEmploymentStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void flaggedWhenNoLongerActive(StaffEmploymentStatus status) {
        member.setEmploymentStatus(status);

        assertThat(service.check(143L)).isEqualTo(Checked.PAID);

        verify(loanService).flagEmploymentAtPayout(loan, status, StaffLoanPayoutStatusService.SYSTEM_ACTOR, 17L);
    }

    // --- a payout confirmed never booked is watched -------------------------------------------------------------

    private StaffLoan failed(LocalDateTime failedAt) {
        return cashLoan(StaffLoanPayoutStatus.FAILED).status(StaffLoanStatus.CANCELLED).cancelledAt(failedAt)
                .cancelledBy("admin").cancellationReason("never booked").payoutFailedAt(failedAt)
                .payoutNotFoundAt(failedAt.minusDays(2)).payoutBankSeenAt(null).payoutLastAnswer(null).build();
    }

    @Test
    @DisplayName("the bank holding a payout confirmed never booked: recorded once, loudly; the loan stays cancelled")
    void paidAfterCancel() {
        loan = failed(NOW.minusDays(3));
        answer = found("PROCESSING");

        assertThat(service.check(143L)).isEqualTo(Checked.PAID_AFTER_CANCEL);

        assertThat(loan.getPayoutPaidAfterCancelAt()).isEqualTo(NOW);
        assertThat(loan.getPayoutBankSeenAt()).isEqualTo(NOW);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.FAILED);
        assertThat(auditedEvents()).containsExactly(StaffLoanPayoutStatusService.PAID_AFTER_CANCEL);
        assertThat(counted(StaffLoanPayoutMetrics.Event.PAID_AFTER_CANCEL)).isEqualTo(1);
        verifyNoInteractions(notifications);

        assertThat(service.check(143L)).as("no longer watched").isEqualTo(Checked.SKIPPED);
    }

    @Test
    @DisplayName("a watched payout the bank holds as declined is no payment: nothing recorded, the borrower not held")
    void watchedDeclined() {
        loan = failed(NOW.minusDays(3));
        answer = found("REJECTED");

        assertThat(service.check(143L)).isEqualTo(Checked.DECLINED);

        assertThat(loan.getPayoutPaidAfterCancelAt()).isNull();
        verify(loans, never()).save(any());
        assertThat(audited()).isEmpty();
    }

    @Test
    @DisplayName("a watched payout still not found changes nothing; one past failed-watch (14 days) is not asked about")
    void watchedNotFound() {
        loan = failed(NOW.minusDays(3));
        answer = notFound();
        assertThat(service.check(143L)).isEqualTo(Checked.NOT_FOUND);
        verify(loans, never()).save(any());

        loan = failed(NOW.minusDays(15));
        org.mockito.Mockito.clearInvocations(inquiry);
        assertThat(service.check(143L)).isEqualTo(Checked.SKIPPED);
        verifyNoInteractions(inquiry);
    }

    // --- a person who asked the bank: paid ----------------------------------------------------------------------

    @ParameterizedTest(name = "confirmed paid from {0}: PAID, DISBURSED, told, audited with the note's hash")
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"BOOKED", "IN_DOUBT"})
    void confirmPaid(StaffLoanPayoutStatus from) {
        loan = cashLoan(from).payoutNotFoundAt(from == StaffLoanPayoutStatus.IN_DOUBT ? NOW.minusDays(2) : null)
                .build();

        service.confirmPaid(143L, " BR-778812 ", "Called Tafadzwa at the bank; paid on the 28th", FINANCE);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.PAID);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(loan.getDisbursementReference()).isEqualTo(REFERENCE);
        assertThat(loan.getPayoutNotFoundAt()).isNull();
        assertThat(loan.getPayoutBankSeenAt()).isNotNull();
        verify(notifications).notifyLoan(eq(loan), eq(StaffNotificationTemplate.LOAN_PAID_OUT), anyString());
        assertThat(counted(StaffLoanPayoutMetrics.Event.CONFIRMED_PAID)).isEqualTo(1);
        List<AuditLog> entries = audited();
        assertThat(entries).extracting(AuditLog::getEventType).containsExactly(
                StaffLoanPayoutStatusService.CONFIRMED_PAID, StaffLoanPayoutStatusService.PAID_OUT);
        assertThat(entries.getFirst().getDetail()).isEqualTo("reference:" + REFERENCE + ";was:" + from
                + ";bankReference:BR-778812");
        assertThat(entries.getFirst().getPayloadHash()).isEqualTo(AuditService.sha256Hex(
                "Called Tafadzwa at the bank; paid on the 28th"));
        assertThat(entries.getFirst().getActorId()).isEqualTo("finance1");
        assertThat(entries.get(1).getDetail()).endsWith(";via:operator");
        InOrder order = inOrder(members, loans);
        order.verify(members).lockRegister(StaffRegisterService.REGISTER_LOCK);
        order.verify(loans).lockById(143L);
    }

    @Test
    @DisplayName("confirmed paid to a borrower who has left: flagged")
    void confirmPaidFlags() {
        member.setEmploymentStatus(StaffEmploymentStatus.RESIGNED);

        service.confirmPaid(143L, "BR-1", "note", FINANCE);

        verify(loanService).flagEmploymentAtPayout(loan, StaffEmploymentStatus.RESIGNED, "finance1", 17L);
    }

    @ParameterizedTest(name = "a payout {0} cannot be confirmed paid")
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"READY", "SENDING", "REFUSED", "PAID", "FAILED",
            "WITHDRAWN"})
    void confirmPaidRefused(StaffLoanPayoutStatus status) {
        StaffLoanStatus loanStatus = switch (status) {
            case PAID -> StaffLoanStatus.DISBURSED;
            case FAILED, WITHDRAWN -> StaffLoanStatus.CANCELLED;
            default -> StaffLoanStatus.AWAITING_DISBURSEMENT;
        };
        loan = cashLoan(status).status(loanStatus).build();

        assertThatThrownBy(() -> service.confirmPaid(143L, "BR-1", "note", FINANCE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("payout " + status);
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("confirm paid: an unknown loan is 404, a voucher loan 409, a blank bank reference or note 400")
    void confirmPaidOtherwiseRefused() {
        loan = null;
        assertThatThrownBy(() -> service.confirmPaid(143L, "BR-1", "note", FINANCE))
                .isInstanceOf(NotFoundException.class);
        loan = StaffLoan.builder().id(143L).reference(REFERENCE).status(StaffLoanStatus.AWAITING_DISBURSEMENT).build();
        assertThatThrownBy(() -> service.confirmPaid(143L, "BR-1", "note", FINANCE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("voucher loan");
        assertThatThrownBy(() -> service.confirmPaid(143L, " ", "note", FINANCE))
                .isInstanceOf(ValidationException.class).hasMessage("bankReference is required");
        assertThatThrownBy(() -> service.confirmPaid(143L, "BR-1", null, FINANCE))
                .isInstanceOf(ValidationException.class).hasMessage("note is required");
        assertThatThrownBy(() -> service.confirmPaid(143L, "B".repeat(65), "note", FINANCE))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("the borrower, by their ID number or their phone, cannot confirm their own payout: 403")
    void confirmPaidByAParty() {
        assertThatThrownBy(() -> service.confirmPaid(143L, "BR-1", "note",
                user("chipo", "63-2345678-B-42", null))).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("chipo is a party to staff loan " + REFERENCE);
        assertThatThrownBy(() -> service.confirmPaid(143L, "BR-1", "note", user("chipo2", null, "+263 77 212 3123"))
        ).isInstanceOf(AccessDeniedException.class);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        verifyNoInteractions(notifications);
    }

    // --- a person who asked the bank: never booked --------------------------------------------------------------

    private StaffLoan.StaffLoanBuilder notBookable() {
        return cashLoan(StaffLoanPayoutStatus.IN_DOUBT).payoutNotFoundAt(NOW.minusHours(24))
                .payoutCheckedAt(NOW.minusMinutes(2));
    }

    @Test
    @DisplayName("IN_DOUBT, never seen by the bank, missing for 24 hours: FAILED, the loan cancelled, the borrower told")
    void confirmNotBooked() {
        loan = notBookable().build();

        service.confirmNotBooked(143L, "BR operations confirmed no booking under SGL-2026-000143", FINANCE);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.FAILED);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(loan.getPayoutFailedAt()).isEqualTo(NOW);
        assertThat(loan.getCancelledBy()).isEqualTo("finance1");
        assertThat(loan.getCancellationReason()).contains("never booked");
        verify(notifications).notifyLoan(loan, StaffNotificationTemplate.LOAN_NOT_PAID_OUT,
                StaffNotificationTemplate.LOAN_NOT_PAID_OUT.notPaidOutText(REFERENCE));
        assertThat(counted(StaffLoanPayoutMetrics.Event.CONFIRMED_NOT_BOOKED)).isEqualTo(1);
        List<AuditLog> entries = audited();
        assertThat(entries).extracting(AuditLog::getEventType).containsExactly(
                StaffLoanPayoutStatusService.CONFIRMED_NOT_BOOKED, StaffLoanService.CANCELLED);
        assertThat(entries.getFirst().getPayloadHash()).isEqualTo(AuditService.sha256Hex(
                "BR operations confirmed no booking under SGL-2026-000143"));
        assertThat(entries.getFirst().getDetail()).isEqualTo("reference:" + REFERENCE + ";notFoundSince:"
                + NOW.minusHours(24));
    }

    @Test
    @DisplayName("held by the bank as declined for 24 hours, and still lately: never paid, even though BOOKED; FAILED,"
            + " the loan cancelled, the borrower told")
    void confirmDeclinedNotBooked() {
        loan = cashLoan(StaffLoanPayoutStatus.BOOKED).payoutBankStatus("REJECTED").payoutDeclinedAt(NOW.minusHours(25))
                .payoutCheckedAt(NOW.minusMinutes(1)).build();

        service.confirmNotBooked(143L, "The bank's credit desk rejected the booking", FINANCE);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.FAILED);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(loan.getCancellationReason()).contains("never paid").contains("REJECTED");
        verify(notifications).notifyLoan(loan, StaffNotificationTemplate.LOAN_NOT_PAID_OUT,
                StaffNotificationTemplate.LOAN_NOT_PAID_OUT.notPaidOutText(REFERENCE));
        assertThat(audited().getFirst().getDetail()).isEqualTo("reference:" + REFERENCE + ";declinedSince:"
                + NOW.minusHours(25) + ";bankStatus:REJECTED");
    }

    @Test
    @DisplayName("not booked is refused for a BOOKED payout, one the bank has seen, one never reported missing, or"
            + " missing for less than not-booked-min-age")
    void confirmNotBookedRefused() {
        loan = cashLoan(StaffLoanPayoutStatus.BOOKED).payoutNotFoundAt(NOW.minusDays(3))
                .payoutCheckedAt(NOW.minusMinutes(1)).build();
        assertThatThrownBy(() -> service.confirmNotBooked(143L, "note", FINANCE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("the bank took its payout (BOOKED)");

        loan = cashLoan(StaffLoanPayoutStatus.BOOKED).payoutBankStatus("REJECTED").payoutDeclinedAt(NOW.minusHours(2))
                .payoutCheckedAt(NOW.minusMinutes(1)).build();
        assertThatThrownBy(() -> service.confirmNotBooked(143L, "note", FINANCE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("held it as REJECTED only since");

        loan = notBookable().payoutBankSeenAt(NOW.minusDays(1)).build();
        assertThatThrownBy(() -> service.confirmNotBooked(143L, "note", FINANCE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("the bank has said it holds it");

        loan = notBookable().payoutNotFoundAt(null).build();
        assertThatThrownBy(() -> service.confirmNotBooked(143L, "note", FINANCE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("never reported it missing");

        loan = notBookable().payoutNotFoundAt(NOW.minusHours(23)).build();
        assertThatThrownBy(() -> service.confirmNotBooked(143L, "note", FINANCE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("only since 2026-10-28T11:00:00+02:00")
                .hasMessageContaining("from 2026-10-29T11:00:00+02:00");

        loan = notBookable().payoutCheckedAt(NOW.minusMinutes(7)).build();
        assertThatThrownBy(() -> service.confirmNotBooked(143L, "note", FINANCE))
                .as("missing for 24 hours, but the bank has not said so lately (2 x PT3M)")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("last answered at 2026-10-29T09:53:00+02:00")
                .hasMessageContaining("check its status now, then record it within 6 minutes");

        loan = notBookable().build();
        when(audit.hasRecorded(StaffLoanPayoutService.BOOKED_NOT_RECORDED, "STAFF_LOAN", "143")).thenReturn(true);
        assertThatThrownBy(() -> service.confirmNotBooked(143L, "note", FINANCE))
                .as("the bank answered its booking 2xx, and that could not be recorded on the loan")
                .isInstanceOf(ConflictException.class).hasMessageContaining("answered its booking with a 2xx");

        loan = cashLoan(StaffLoanPayoutStatus.READY).build();
        assertThatThrownBy(() -> service.confirmNotBooked(143L, "note", FINANCE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("READY");

        loan = null;
        assertThatThrownBy(() -> service.confirmNotBooked(143L, "note", FINANCE))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(notifications);
        assertThat(counted(StaffLoanPayoutMetrics.Event.CONFIRMED_NOT_BOOKED)).isZero();
    }

    @Test
    @DisplayName("the borrower cannot record their own payout as never booked: 403; a blank note is 400")
    void confirmNotBookedByAParty() {
        loan = notBookable().build();

        assertThatThrownBy(() -> service.confirmNotBooked(143L, "note", user("chipo", "632345678b42", null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.confirmNotBooked(143L, " ", FINANCE))
                .isInstanceOf(ValidationException.class);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("each outcome says whether a run may carry on")
    void carriesOn() {
        assertThat(java.util.Arrays.stream(Checked.values()).filter(checked -> !checked.carriesOn()))
                .containsExactly(Checked.UNANSWERED, Checked.ERROR);
    }
}
