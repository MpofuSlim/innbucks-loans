package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Writing off staff loans, and reversing a write-off, under maker-checker (FR-GEN-011): which loans each applies to,
 * who may propose, decide and withdraw, and that approval changes the loan, audited on both. In-memory repositories;
 * the clock stands at Tuesday 15 December 2026, 10:20:31 in Harare.
 */
class StaffLoanWriteOffServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 15, 8, 20, 31);

    private final List<StaffLoan> loans = new ArrayList<>();
    private final List<Voucher> vouchers = new ArrayList<>();
    private final List<StaffLoanWriteOff> requests = new ArrayList<>();
    private StaffMemberRepository memberRepository;
    private StaffLoanWriteOffRepository repository;
    private AuthService authService;
    private AuditService auditService;
    private StaffLoanWriteOffService service;

    /** Nyasha Dube's loan, paid out on 8 October; its voucher expired on 7 November. */
    private StaffLoan nyasha;
    /** Chipo Banda's loan, paid out, with a voucher partly spent and still open. */
    private StaffLoan chipo;
    /** Tatenda Mhlanga's loan, written off in September, and the one she took since under Credit's override. */
    private StaffLoan tatendaWrittenOff;
    private StaffLoan tatendaOpen;

    @BeforeEach
    void setUp() {
        nyasha = loan(151L, "SGL-2026-000151", 1L, "E1001", StaffLoanStatus.DISBURSED, "250.00");
        chipo = loan(143L, "SGL-2026-000143", 2L, "E1012", StaffLoanStatus.DISBURSED, "300.00");
        tatendaWrittenOff = loan(112L, "SGL-2026-000112", 6L, "E1060", StaffLoanStatus.WRITTEN_OFF, "200.00");
        tatendaOpen = loan(158L, "SGL-2026-000158", 6L, "E1060", StaffLoanStatus.AWAITING_DISBURSEMENT, "120.00");
        vouchers.add(voucher(8L, nyasha, VoucherStatus.ISSUED, LocalDateTime.of(2026, 11, 7, 21, 59, 59)));
        vouchers.add(voucher(7L, chipo, VoucherStatus.PARTIALLY_REDEEMED, LocalDateTime.of(2026, 12, 20, 21, 59, 59)));

        StaffLoanRepository loanRepository = mock(StaffLoanRepository.class);
        when(loanRepository.findById(anyLong())).thenAnswer(i -> loanById(i.getArgument(0)));
        when(loanRepository.lockById(anyLong())).thenAnswer(i -> loanById(i.getArgument(0)));
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loanRepository.findAllById(any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            return loans.stream().filter(loan -> ids.contains(loan.getId())).toList();
        });
        when(loanRepository.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(anyLong(), any())).thenAnswer(i -> {
            Collection<StaffLoanStatus> statuses = i.getArgument(1);
            return loans.stream().filter(loan -> loan.getStaffMemberId().equals(i.getArgument(0))
                    && statuses.contains(loan.getStatus())).reduce((first, second) -> second);
        });

        VoucherRepository voucherRepository = mock(VoucherRepository.class);
        when(voucherRepository.findFirstByStaffMemberIdAndLoanAccountOrderByIdDesc(anyLong(), anyString()))
                .thenAnswer(i -> vouchers.stream().filter(v -> v.getStaffMemberId().equals(i.getArgument(0))
                        && v.getLoanAccount().equals(i.getArgument(1))).findFirst());

        repository = mock(StaffLoanWriteOffRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(i -> store(i.getArgument(0)));
        when(repository.save(any())).thenAnswer(i -> store(i.getArgument(0)));
        when(repository.findByIdForUpdate(anyLong())).thenAnswer(i -> requests.stream()
                .filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(repository.findByStaffLoanIdAndStatus(anyLong(), any())).thenAnswer(i -> requests.stream()
                .filter(r -> r.getStaffLoanId().equals(i.getArgument(0)) && r.getStatus() == i.getArgument(1))
                .findFirst());

        memberRepository = mock(StaffMemberRepository.class);
        authService = mock(AuthService.class);
        as("credit1");
        auditService = mock(AuditService.class);
        service = new StaffLoanWriteOffService(repository, loanRepository, memberRepository, voucherRepository,
                authService, auditService, new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-12-15T08:20:31Z"),
                ZoneOffset.UTC)));
    }

    private StaffLoan loan(Long id, String reference, Long memberId, String employeeNumber, StaffLoanStatus status,
                           String amount) {
        boolean paidOut = status != StaffLoanStatus.AWAITING_DISBURSEMENT;
        StaffLoan loan = StaffLoan.builder().id(id).reference(reference).staffMemberId(memberId)
                .employeeNumber(employeeNumber).fullName("Borrower " + employeeNumber).amount(new BigDecimal(amount))
                .currency("USD").totalRepayable(new BigDecimal(amount)).dueDate(LocalDate.of(2026, 11, 20))
                .status(status).acceptedAt(NOW.minusDays(60))
                .disbursedAt(paidOut ? NOW.minusDays(60) : null)
                .settledAt(status == StaffLoanStatus.WRITTEN_OFF ? NOW.minusDays(90) : null).build();
        loans.add(loan);
        return loan;
    }

    private static Voucher voucher(Long id, StaffLoan loan, VoucherStatus status, LocalDateTime expiresAt) {
        return Voucher.builder().id(id).loanAccount(loan.getReference()).staffMemberId(loan.getStaffMemberId())
                .status(status).expiresAt(expiresAt).build();
    }

    private Optional<StaffLoan> loanById(Long id) {
        return loans.stream().filter(loan -> loan.getId().equals(id)).findFirst();
    }

    private StaffLoanWriteOff store(StaffLoanWriteOff request) {
        if (request.getId() == null) {
            request.setId((long) requests.size() + 1);
            requests.add(request);
        }
        return request;
    }

    private void as(String username) {
        when(authService.getLoggedInUsername()).thenReturn(username);
    }

    private static ProposeStaffLoanWriteOffRequest propose(StaffLoan loan, StaffLoanWriteOffKind kind) {
        return ProposeStaffLoanWriteOffRequest.builder().staffLoanId(loan.getId()).kind(kind)
                .reason(" Recovery from terminal benefits failed ").build();
    }

    private static StaffLoanWriteOffDecisionRequest decision(StaffLoanWriteOffDecision decision, String comment) {
        return StaffLoanWriteOffDecisionRequest.builder().decision(decision).comment(comment).build();
    }

    private List<AuditLog> audits() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    private List<String> audited() {
        return audits().stream().map(AuditLog::getEventType).toList();
    }

    @Test
    @DisplayName("a write-off is proposed for a paid-out loan whose voucher has lapsed, at what the loan owes; one may"
            + " wait per loan; an unknown loan is a 404")
    void proposesAWriteOff() {
        StaffLoanWriteOffResponse proposed = service.propose(propose(nyasha, StaffLoanWriteOffKind.WRITE_OFF));

        assertThat(proposed.status()).isEqualTo(StaffLoanWriteOffStatus.PENDING);
        assertThat(proposed.loanReference()).isEqualTo("SGL-2026-000151");
        assertThat(proposed.employeeNumber()).isEqualTo("E1001");
        assertThat(proposed.loanStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(proposed.amount()).isEqualByComparingTo("250.00");
        assertThat(proposed.currency()).isEqualTo("USD");
        assertThat(proposed.reason()).isEqualTo("Recovery from terminal benefits failed");
        assertThat(proposed.proposedBy()).isEqualTo("credit1");
        assertThat(proposed.proposedAt()).isEqualTo(NOW);
        assertThat(nyasha.getStatus()).as("nothing changes until it is approved").isEqualTo(StaffLoanStatus.DISBURSED);
        assertThatThrownBy(() -> service.propose(propose(nyasha, StaffLoanWriteOffKind.WRITE_OFF)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000151 already has a write-off request waiting for a decision (1);"
                        + " approve, reject or withdraw it first");
        assertThatThrownBy(() -> service.propose(ProposeStaffLoanWriteOffRequest.builder().staffLoanId(999L)
                .kind(StaffLoanWriteOffKind.WRITE_OFF).reason("x").build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Staff loan 999 not found");
        assertThat(audits()).singleElement().satisfies(audit -> {
            assertThat(audit.getEventType()).isEqualTo("STAFF_LOAN_WRITE_OFF_PROPOSED");
            assertThat(audit.getEntityType()).isEqualTo("STAFF_LOAN_WRITE_OFF");
            assertThat(audit.getDetail()).isEqualTo("loan:SGL-2026-000151;employee:E1001;amount:250.00 USD;reason:"
                    + "Recovery from terminal benefits failed");
        });
    }

    @Test
    @DisplayName("a loan not paid out is cancelled, not written off; one whose voucher can still be spent waits; one"
            + " already written off has nothing to write off")
    void refusesWhatCannotBeWrittenOff() {
        assertThatThrownBy(() -> service.propose(propose(tatendaOpen, StaffLoanWriteOffKind.WRITE_OFF)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000158 has not been paid out: cancel it instead of writing it off");
        assertThatThrownBy(() -> service.propose(propose(chipo, StaffLoanWriteOffKind.WRITE_OFF)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000143's voucher 7 can still be spent, until"
                        + " 2026-12-20T23:59:59+02:00; a loan whose value can still be drawn cannot be written off:"
                        + " wait until it expires");
        vouchers.set(1, voucher(7L, chipo, VoucherStatus.ISSUED, LocalDateTime.of(2026, 12, 20, 21, 59, 59)));
        assertThatThrownBy(() -> service.propose(propose(chipo, StaffLoanWriteOffKind.WRITE_OFF)))
                .hasMessageEndingWith("cancel the voucher or wait until it expires");
        assertThatThrownBy(() -> service.propose(propose(tatendaWrittenOff, StaffLoanWriteOffKind.WRITE_OFF)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000112 is WRITTEN_OFF; only a paid-out loan (DISBURSED) can be"
                        + " written off");
        assertThat(requests).isEmpty();
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("the proposer cannot decide; a rejection needs a reason; approval writes the loan off under the"
            + " register's lock, audited on the request and on the loan")
    void decidesAWriteOff() {
        service.propose(propose(nyasha, StaffLoanWriteOffKind.WRITE_OFF));

        as("CREDIT1");
        assertThatThrownBy(() -> service.decide(1L, decision(StaffLoanWriteOffDecision.APPROVED, null)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("CREDIT1 proposed write-off request 1 and cannot also approve or reject it; another"
                        + " FINANCE user or a SUPER_ADMIN must");
        as("finance1");
        assertThatThrownBy(() -> service.decide(1L, decision(StaffLoanWriteOffDecision.REJECTED, " ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A reason is required to reject a write-off request");

        StaffLoanWriteOffResponse decided = service.decide(1L, decision(StaffLoanWriteOffDecision.APPROVED,
                " Recovery attempts reviewed with Credit "));

        assertThat(decided.status()).isEqualTo(StaffLoanWriteOffStatus.APPROVED);
        assertThat(decided.decidedBy()).isEqualTo("finance1");
        assertThat(decided.decisionComment()).isEqualTo("Recovery attempts reviewed with Credit");
        assertThat(decided.loanStatus()).isEqualTo(StaffLoanStatus.WRITTEN_OFF);
        assertThat(nyasha.getStatus()).isEqualTo(StaffLoanStatus.WRITTEN_OFF);
        assertThat(nyasha.getSettledAt()).isEqualTo(NOW);
        assertThat(nyasha.outstanding()).as("still owed").isEqualByComparingTo("250.00");
        verify(memberRepository, times(3)).lockRegister(StaffRegisterService.REGISTER_LOCK);
        assertThat(audited()).containsExactly("STAFF_LOAN_WRITE_OFF_PROPOSED", "STAFF_LOAN_WRITE_OFF_APPROVED",
                StaffLoanWriteOffService.LOAN_WRITTEN_OFF);
        assertThat(audits().getLast()).satisfies(audit -> {
            assertThat(audit.getEntityType()).isEqualTo("STAFF_LOAN");
            assertThat(audit.getEntityId()).isEqualTo("151");
            assertThat(audit.getActorId()).isEqualTo("finance1");
            assertThat(audit.getStateTransitionDelta()).isEqualTo("{\"from\":\"DISBURSED\",\"to\":\"WRITTEN_OFF\"}");
            assertThat(audit.getDetail()).isEqualTo("reference:SGL-2026-000151;writeOffRequest:1;proposedBy:credit1;"
                    + "amount:250.00 USD");
        });
        assertThatThrownBy(() -> service.decide(1L, decision(StaffLoanWriteOffDecision.REJECTED, "No")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Write-off request 1 is already approved");
        assertThatThrownBy(() -> service.decide(99L, decision(StaffLoanWriteOffDecision.REJECTED, "No")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Write-off request 99 not found");
    }

    @Test
    @DisplayName("approval checks the loan again: if its voucher can be spent again by then, the loan stays as it is,"
            + " and so does the request")
    void approvalRechecksTheLoan() {
        service.propose(propose(nyasha, StaffLoanWriteOffKind.WRITE_OFF));
        vouchers.set(0, voucher(8L, nyasha, VoucherStatus.ISSUED, NOW.plusDays(3)));
        as("finance1");

        assertThatThrownBy(() -> service.decide(1L, decision(StaffLoanWriteOffDecision.APPROVED, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("Staff loan SGL-2026-000151's voucher 8 can still be spent");
        assertThat(nyasha.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(requests.getFirst().getStatus()).isEqualTo(StaffLoanWriteOffStatus.PENDING);

        assertThat(service.decide(1L, decision(StaffLoanWriteOffDecision.REJECTED, "Voucher still live")).status())
                .as("it can still be rejected").isEqualTo(StaffLoanWriteOffStatus.REJECTED);
    }

    @Test
    @DisplayName("a reversal puts a written-off loan back, but not beside another open loan, and only a written-off"
            + " one")
    void reversesAWriteOff() {
        assertThatThrownBy(() -> service.propose(propose(tatendaWrittenOff,
                StaffLoanWriteOffKind.WRITE_OFF_REVERSAL)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Employee E1060 holds staff loan SGL-2026-000158, which is open; putting"
                        + " SGL-2026-000112 back would give them two open loans. It can be reversed once"
                        + " SGL-2026-000158 is closed");
        assertThatThrownBy(() -> service.propose(propose(chipo, StaffLoanWriteOffKind.WRITE_OFF_REVERSAL)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000143 is DISBURSED, not written off, so there is no write-off to"
                        + " reverse");

        tatendaOpen.cancel(NOW, "credit1", "Accepted in error");
        StaffLoanWriteOffResponse proposed = service.propose(propose(tatendaWrittenOff,
                StaffLoanWriteOffKind.WRITE_OFF_REVERSAL));
        assertThat(proposed.amount()).isEqualByComparingTo("200.00");
        as("finance1");
        StaffLoanWriteOffResponse decided = service.decide(proposed.id(),
                decision(StaffLoanWriteOffDecision.APPROVED, null));

        assertThat(decided.loanStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(tatendaWrittenOff.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(tatendaWrittenOff.getSettledAt()).isNull();
        assertThat(audited()).containsExactly("STAFF_LOAN_WRITE_OFF_REVERSAL_PROPOSED",
                "STAFF_LOAN_WRITE_OFF_REVERSAL_APPROVED", StaffLoanWriteOffService.LOAN_REINSTATED);
        assertThat(audits().getLast().getStateTransitionDelta())
                .isEqualTo("{\"from\":\"WRITTEN_OFF\",\"to\":\"DISBURSED\"}");
    }

    @Test
    @DisplayName("only the proposer withdraws, and only while pending")
    void withdraws() {
        service.propose(propose(nyasha, StaffLoanWriteOffKind.WRITE_OFF));

        as("finance1");
        assertThatThrownBy(() -> service.withdraw(1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only credit1, who proposed write-off request 1, can withdraw it; anyone else entitled"
                        + " approves or rejects it");
        as("credit1");
        assertThat(service.withdraw(1L).status()).isEqualTo(StaffLoanWriteOffStatus.WITHDRAWN);
        assertThat(requests.getFirst().getDecidedBy()).isEqualTo("credit1");
        assertThat(nyasha.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThatThrownBy(() -> service.withdraw(1L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Write-off request 1 is already withdrawn");
        assertThat(audited()).containsExactly("STAFF_LOAN_WRITE_OFF_PROPOSED", "STAFF_LOAN_WRITE_OFF_WITHDRAWN");
    }

    @Test
    @DisplayName("the list carries each request's loan and the loan's status now, newest first")
    @SuppressWarnings("unchecked")
    void lists() {
        service.propose(propose(nyasha, StaffLoanWriteOffKind.WRITE_OFF));
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(i ->
                new PageImpl<>(List.copyOf(requests), (Pageable) i.getArgument(1), requests.size()));

        List<StaffLoanWriteOffResponse> listed = service.requests(StaffLoanWriteOffStatus.PENDING,
                StaffLoanWriteOffKind.WRITE_OFF, 151L, PageRequest.of(0, 20)).getContent();

        assertThat(listed).singleElement().satisfies(request -> {
            assertThat(request.loanReference()).isEqualTo("SGL-2026-000151");
            assertThat(request.fullName()).isEqualTo("Borrower E1001");
            assertThat(request.loanStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        });
        verify(repository).findAll(any(Specification.class), eq(PageRequest.of(0, 20,
                Sort.by(Sort.Direction.DESC, "id"))));
    }
}
