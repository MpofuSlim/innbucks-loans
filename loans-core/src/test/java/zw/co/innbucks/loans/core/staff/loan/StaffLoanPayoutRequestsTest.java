package zw.co.innbucks.loans.core.staff.loan;

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
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.user.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a person asks of a cash payout from the portal: "Pay out" sends one READY, or one the bank REFUSED again under
 * the same reference, queued only once that change has committed; never one the bank took or may have taken; never by
 * a party to the loan. "Pay all ready" queues one pass that leaves out the caller's own loans. "Check now", "paid" and
 * "never booked" go to the status service as the signed-in user.
 */
class StaffLoanPayoutRequestsTest {

    private static final String REFERENCE = "SGL-2026-000143";

    private final StaffLoanRepository loans = mock(StaffLoanRepository.class);
    private final StaffMemberRepository members = mock(StaffMemberRepository.class);
    private final StaffLoanPayoutDispatcher dispatcher = mock(StaffLoanPayoutDispatcher.class);
    private final StaffLoanPayoutStatusService statusService = mock(StaffLoanPayoutStatusService.class);
    private final AuthService auth = mock(AuthService.class);
    private final AuditService audit = mock(AuditService.class);
    private final StaffLoanService loanService = mock(StaffLoanService.class);
    private final CountingTransactionManager transactions = new CountingTransactionManager();
    private final StaffLoanPayoutRequests requests = new StaffLoanPayoutRequests(loans, members, loanService,
            dispatcher, statusService, auth, audit, transactions);
    /** What the loan's payout status was, and whether a transaction was open, when its summary was read. */
    private final List<String> summaries = new ArrayList<>();

    private final Map<Long, StaffLoan> stored = new HashMap<>();
    private final StaffMember chipo = member(2L, "632345678B42", "263772123123");
    private final User finance = user("finance1", "63-1111111-A-11", "0771000000");
    private final List<Boolean> committedWhenQueued = new ArrayList<>();

    StaffLoanPayoutRequestsTest() {
        when(auth.getLoggedInUser()).thenReturn(finance);
        when(auth.getLoggedInUsername()).thenReturn("finance1");
        when(loans.lockById(anyLong())).thenAnswer(i -> Optional.ofNullable(stored.get((Long) i.getArgument(0))));
        when(loans.save(any())).thenAnswer(i -> i.getArgument(0));
        when(members.findById(2L)).thenReturn(Optional.of(chipo));
        when(dispatcher.request(anyLong())).thenAnswer(i -> {
            committedWhenQueued.add(!TransactionSynchronizationManager.isActualTransactionActive()
                    && transactions.committed() > 0);
            return true;
        });
        when(dispatcher.requestSweep(any())).thenReturn(true);
        when(loanService.summary(anyLong())).thenAnswer(i -> {
            StaffLoan loan = stored.get((Long) i.getArgument(0));
            summaries.add(loan.getPayoutStatus() + (TransactionSynchronizationManager.isActualTransactionActive()
                    ? " in its transaction" : " outside a transaction"));
            return mock(StaffLoanResponse.class);
        });
    }

    private static StaffLoan.StaffLoanBuilder cashLoan(long id, StaffLoanPayoutStatus payout) {
        return StaffLoan.builder().id(id).reference(String.format("SGL-2026-%06d", id)).staffMemberId(2L).offerId(31L)
                .employeeNumber("E1012").fullName("Chipo Banda").msisdn("263772123123").grade("C4")
                .amount(new BigDecimal("150.00")).currency("USD").interestRate(BigDecimal.ZERO)
                .totalRepayable(new BigDecimal("150.00")).dueDate(LocalDate.of(2026, 11, 20))
                .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS)
                .status(StaffLoanStatus.AWAITING_DISBURSEMENT).acceptedAt(LocalDateTime.of(2026, 10, 29, 7, 0))
                .payoutMethod(StaffLoanPayoutMethod.CASH).payoutDestination(StaffLoanPayoutDestination.VEENGU_WALLET)
                .payoutAccount("263772123123").payoutStatus(payout);
    }

    private StaffLoan store(StaffLoan loan) {
        stored.put(loan.getId(), loan);
        return loan;
    }

    private static StaffMember member(long id, String nationalId, String msisdn) {
        return StaffMember.builder().id(id).employeeNumber("E" + id).fullName("Member " + id).nationalId(nationalId)
                .msisdn(msisdn).grade("C4").employmentStatus(StaffEmploymentStatus.ACTIVE)
                .engagementDate(LocalDate.of(2015, 2, 2)).walletAccountNumber(msisdn).build();
    }

    private static User user(String username, String idNumber, String mobile) {
        User user = new User();
        user.setUsername(username);
        user.setIdNumber(idNumber);
        user.setMobileNumber(mobile);
        return user;
    }

    private List<AuditLog> audited() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit, org.mockito.Mockito.atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    // --- Pay out ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a READY payout is queued once the request's transaction has committed; audited as asked for")
    void sendReady() {
        StaffLoan loan = store(cashLoan(143L, StaffLoanPayoutStatus.READY).payoutMessage("Not sent: earlier").build());

        StaffLoanResponse answered = requests.sendNow(143L);

        verify(dispatcher).request(143L);
        assertThat(committedWhenQueued).containsExactly(true);
        assertThat(summaries).as("the answer is the loan READY, read before it was queued, so the payout thread cannot"
                + " have claimed it yet").containsExactly("READY in its transaction");
        InOrder order = inOrder(loanService, dispatcher);
        order.verify(loanService).summary(143L);
        order.verify(dispatcher).request(143L);
        assertThat(answered).isNotNull();
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
        verify(loans, never()).save(any());
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutRequests.REQUESTED);
            assertThat(entry.getActorId()).isEqualTo("finance1");
            assertThat(entry.getChannelUsed()).isEqualTo("admin-portal");
            assertThat(entry.getEntityId()).isEqualTo("143");
            assertThat(entry.getDetail()).isEqualTo("reference:" + REFERENCE + ";was:READY;queued:true");
        });
    }

    @Test
    @DisplayName("a REFUSED payout is READY again under the same reference, then queued")
    void sendRefusedAgain() {
        StaffLoan loan = store(cashLoan(143L, StaffLoanPayoutStatus.REFUSED).payoutMessage("The bank refused it")
                .payoutLastAnswer("{\"responseCode\":\"400\"}").build());

        requests.sendNow(143L);

        assertThat(summaries).containsExactly("READY in its transaction");
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
        assertThat(loan.getPayoutMessage()).isNull();
        assertThat(loan.getReference()).isEqualTo(REFERENCE);
        assertThat(loan.getPayoutLastAnswer()).as("the refusal stays as evidence").contains("400");
        verify(loans).save(loan);
        assertThat(committedWhenQueued).containsExactly(true);
        assertThat(audited()).singleElement().extracting(AuditLog::getDetail)
                .isEqualTo("reference:" + REFERENCE + ";was:REFUSED;queued:true");
    }

    @ParameterizedTest(name = "a payout {0} is never sent from here: 409, nothing queued")
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"SENDING", "BOOKED", "IN_DOUBT"})
    void inFlightNeverSentAgain(StaffLoanPayoutStatus status) {
        store(cashLoan(143L, status).build());

        assertThatThrownBy(() -> requests.sendNow(143L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan " + REFERENCE + " has payout " + status + " (loan AWAITING_DISBURSEMENT); only"
                        + " a payout READY to send, or REFUSED by the bank, can be sent. The bank took it or may have"
                        + " taken it, so it is never sent again: check its status instead");

        verifyNoInteractions(dispatcher, audit);
        assertThat(transactions.rolledBack()).isEqualTo(1);
    }

    @Test
    @DisplayName("a payout settled or ended is 409; a voucher loan is 409; no loan is 404")
    void otherwiseRefused() {
        store(cashLoan(143L, StaffLoanPayoutStatus.PAID).status(StaffLoanStatus.DISBURSED).build());
        assertThatThrownBy(() -> requests.sendNow(143L)).isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan " + REFERENCE + " has payout PAID (loan DISBURSED); only a payout READY to"
                        + " send, or REFUSED by the bank, can be sent");

        store(cashLoan(143L, StaffLoanPayoutStatus.WITHDRAWN).status(StaffLoanStatus.CANCELLED).build());
        assertThatThrownBy(() -> requests.sendNow(143L)).isInstanceOf(ConflictException.class);

        store(StaffLoan.builder().id(143L).reference(REFERENCE).status(StaffLoanStatus.AWAITING_DISBURSEMENT).build());
        assertThatThrownBy(() -> requests.sendNow(143L)).isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan " + REFERENCE + " is a voucher loan: it is not paid out in cash");

        assertThatThrownBy(() -> requests.sendNow(999L)).isInstanceOf(NotFoundException.class)
                .hasMessage("Staff loan 999 not found");
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("the borrower, by ID or phone, cannot send their own payout: 403, nothing changed or queued")
    void partyRefused() {
        StaffLoan loan = store(cashLoan(143L, StaffLoanPayoutStatus.REFUSED).build());
        for (User party : List.of(user("chipo", "63-2345678-B-42", null), user("chipo", null, "0772123123"))) {
            when(auth.getLoggedInUser()).thenReturn(party);

            assertThatThrownBy(() -> requests.sendNow(143L))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("chipo is a party to staff loan " + REFERENCE + " and cannot send its payout; someone"
                            + " else must");
        }
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.REFUSED);
        verifyNoInteractions(dispatcher, audit);
    }

    @Test
    @DisplayName("a full queue: 503's exception; the REFUSED payout is READY, nothing sent, audited as not queued")
    void queueFull() {
        StaffLoan loan = store(cashLoan(143L, StaffLoanPayoutStatus.REFUSED).build());
        when(dispatcher.request(143L)).thenReturn(false);

        assertThatThrownBy(() -> requests.sendNow(143L))
                .isInstanceOf(StaffLoanPayoutQueueFullException.class)
                .hasMessage("The payout queue is full, so staff loan " + REFERENCE + " was not queued; it is READY,"
                        + " and nothing was sent. Try again in a few minutes.");

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
        assertThat(audited()).singleElement().extracting(AuditLog::getDetail)
                .isEqualTo("reference:" + REFERENCE + ";was:REFUSED;queued:false");
    }

    @Test
    @DisplayName("never asked inside a transaction: it would queue before the commit")
    void neverInsideATransaction() {
        store(cashLoan(143L, StaffLoanPayoutStatus.READY).build());
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> requests.sendNow(143L)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(dispatcher);
    }

    // --- Pay all ready --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("pay all ready: one pass queued, in chunks, leaving out the caller's own loans; counted and audited")
    void payAllReady() {
        // 150 READY loans across two chunks; the caller's own phone is the borrower of loans 7 and 120.
        StaffMember own = member(5L, "631111111A11", "263771000000");
        when(members.findAllById(any())).thenAnswer(i -> {
            List<StaffMember> found = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) {
                found.add((Long) id == 5L ? own : chipo);
            }
            return found;
        });
        for (long id = 1; id <= 150; id++) {
            store(cashLoan(id, StaffLoanPayoutStatus.READY).staffMemberId(id == 7 || id == 120 ? 5L : 2L).build());
        }
        when(loans.findPayoutReadyIds(anyLong(), any(Pageable.class))).thenAnswer(i -> {
            long after = i.getArgument(0);
            Pageable chunk = i.getArgument(1);
            return stored.keySet().stream().sorted().filter(id -> id > after).limit(chunk.getPageSize()).toList();
        });
        when(loans.findAllById(any())).thenAnswer(i -> {
            List<StaffLoan> found = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) {
                found.add(stored.get((Long) id));
            }
            return found;
        });

        StaffLoanPayoutSweepResponse queued = requests.payAllReady();

        assertThat(queued).isEqualTo(new StaffLoanPayoutSweepResponse(148, 2));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> skipped = ArgumentCaptor.forClass(Collection.class);
        verify(dispatcher).requestSweep(skipped.capture());
        assertThat(skipped.getValue()).containsExactlyInAnyOrder(7L, 120L);
        assertThat(audited()).singleElement().satisfies(entry -> {
            assertThat(entry.getEventType()).isEqualTo(StaffLoanPayoutRequests.REQUESTED);
            assertThat(entry.getEntityId()).isNull();
            assertThat(entry.getDetail()).isEqualTo("sweep:ready=148;skippedOwn=2");
        });
    }

    @Test
    @DisplayName("pay all ready with nothing READY still queues a pass (it finds nothing); a full queue is refused")
    void payAllReadyQueueFull() {
        when(loans.findPayoutReadyIds(anyLong(), any(Pageable.class))).thenReturn(List.of());
        assertThat(requests.payAllReady()).isEqualTo(new StaffLoanPayoutSweepResponse(0, 0));
        verify(dispatcher).requestSweep(Set.of());

        when(dispatcher.requestSweep(any())).thenReturn(false);
        assertThatThrownBy(requests::payAllReady)
                .isInstanceOf(StaffLoanPayoutQueueFullException.class)
                .hasMessage("The payout queue is full, so the 0 ready payout(s) were not queued; they are READY, and"
                        + " nothing was sent. Try again in a few minutes.");
    }

    // --- the status service, as the signed-in user -----------------------------------------------------------------

    @Test
    @DisplayName("check now, paid and never booked go to the status service as the signed-in user")
    void statusActionsAsTheUser() {
        when(statusService.checkNow(143L, "finance1")).thenReturn(StaffLoanPayoutStatusService.Checked.PAID);

        assertThat(requests.checkNow(143L)).isEqualTo(StaffLoanPayoutStatusService.Checked.PAID);
        requests.confirmPaid(143L, "IBK-77120", "Confirmed by T. Ncube, InnBucks operations");
        requests.confirmNotBooked(143L, "InnBucks ops: no booking under the reference");

        verify(statusService).confirmPaid(143L, "IBK-77120", "Confirmed by T. Ncube, InnBucks operations", finance);
        verify(statusService).confirmNotBooked(143L, "InnBucks ops: no booking under the reference", finance);
        verify(statusService, never()).check(anyLong(), anyString());
        verifyNoInteractions(dispatcher);
    }
}
