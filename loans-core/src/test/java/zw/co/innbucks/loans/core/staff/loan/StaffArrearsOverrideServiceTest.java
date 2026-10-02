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
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.offer.StaffArrearsOverride;
import zw.co.innbucks.loans.core.staff.offer.StaffArrearsOverrideRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffArrearsOverrideStatus;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Credit's arrears overrides under maker-checker (FR-SGL-014): only for a member who owes a written-off loan, with a
 * last day at most 90 days ahead; who may propose, decide, withdraw and revoke; what approving replaces; and that the
 * loan accepted under one uses it up. In-memory repositories; the clock stands at Tuesday 6 October 2026, 09:30:12 in
 * Harare.
 */
class StaffArrearsOverrideServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 7, 30, 12);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private final List<StaffArrearsOverride> overrides = new ArrayList<>();
    private final List<StaffLoan> loans = new ArrayList<>();
    private StaffMember member;
    private boolean owesWrittenOff = true;
    private StaffMemberRepository memberRepository;
    private StaffArrearsOverrideRepository repository;
    private AuthService authService;
    private AuditService auditService;
    private StaffArrearsOverrideService service;

    @BeforeEach
    void setUp() {
        member = StaffMember.builder().id(6L).employeeNumber("E1060").fullName("Tatenda Mhlanga").grade("C4")
                .employmentStatus(StaffEmploymentStatus.ACTIVE).build();
        memberRepository = mock(StaffMemberRepository.class);
        when(memberRepository.findByEmployeeNumber(any())).thenAnswer(i -> "E1060".equals(i.getArgument(0))
                ? Optional.of(member) : Optional.empty());
        when(memberRepository.findById(6L)).thenAnswer(i -> Optional.of(member));
        when(memberRepository.findAllById(any())).thenAnswer(i -> List.of(member));

        repository = mock(StaffArrearsOverrideRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(i -> store(i.getArgument(0)));
        when(repository.save(any())).thenAnswer(i -> store(i.getArgument(0)));
        when(repository.findByIdForUpdate(anyLong())).thenAnswer(i -> overrides.stream()
                .filter(o -> o.getId().equals(i.getArgument(0))).findFirst());
        when(repository.findForUpdate(anyLong(), any())).thenAnswer(i -> overrides.stream()
                .filter(o -> o.getStaffMemberId().equals(i.getArgument(0)) && o.getStatus() == i.getArgument(1))
                .findFirst());
        when(repository.findByStaffMemberIdAndStatus(anyLong(), any())).thenAnswer(i -> overrides.stream()
                .filter(o -> o.getStaffMemberId().equals(i.getArgument(0)) && o.getStatus() == i.getArgument(1))
                .findFirst());

        StaffLoanRepository loanRepository = mock(StaffLoanRepository.class);
        when(loanRepository.existsByStaffMemberIdAndStatus(6L, StaffLoanStatus.WRITTEN_OFF))
                .thenAnswer(i -> owesWrittenOff);
        when(loanRepository.findAllById(any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            return loans.stream().filter(loan -> ids.contains(loan.getId())).toList();
        });

        authService = mock(AuthService.class);
        as("credit1");
        auditService = mock(AuditService.class);
        service = new StaffArrearsOverrideService(repository, memberRepository, loanRepository, authService,
                auditService, new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-10-06T07:30:12Z"),
                ZoneOffset.UTC)));
    }

    private StaffArrearsOverride store(StaffArrearsOverride override) {
        if (override.getId() == null) {
            override.setId((long) overrides.size() + 1);
            overrides.add(override);
        }
        return override;
    }

    private void as(String username) {
        when(authService.getLoggedInUsername()).thenReturn(username);
    }

    private static ProposeStaffArrearsOverrideRequest propose(String employeeNumber, LocalDate validUntil) {
        return ProposeStaffArrearsOverrideRequest.builder().employeeNumber(employeeNumber).validUntil(validUntil)
                .reason(" Repayment plan agreed with Finance ").build();
    }

    private static StaffArrearsOverrideDecisionRequest decision(StaffArrearsOverrideDecision decision,
                                                                String comment) {
        return StaffArrearsOverrideDecisionRequest.builder().decision(decision).comment(comment).build();
    }

    private StaffArrearsOverride approved(LocalDate validUntil) {
        return store(StaffArrearsOverride.builder().staffMemberId(6L).reason("Repayment plan agreed")
                .validUntil(validUntil).status(StaffArrearsOverrideStatus.APPROVED).proposedBy("credit1")
                .proposedAt(NOW).decidedBy("credit2").decidedAt(NOW).build());
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
    @DisplayName("a proposal needs a written-off loan and a last day from today to 90 days ahead; one may wait per"
            + " member; an unknown employee is a 404")
    void proposes() {
        StaffArrearsOverrideResponse proposed = service.propose(propose(" e1060 ", TODAY.plusDays(90)));

        assertThat(proposed.status()).isEqualTo(StaffArrearsOverrideStatus.PENDING);
        assertThat(proposed.employeeNumber()).isEqualTo("E1060");
        assertThat(proposed.reason()).isEqualTo("Repayment plan agreed with Finance");
        assertThat(proposed.validUntil()).isEqualTo(LocalDate.of(2027, 1, 4));
        assertThat(proposed.proposedBy()).isEqualTo("credit1");
        assertThat(proposed.proposedAt()).isEqualTo(NOW);
        assertThat(proposed.inForce()).as("only an approved override is in force or not").isNull();
        assertThatThrownBy(() -> service.propose(propose("E1060", TODAY)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Employee E1060 already has an arrears override waiting for a decision (1); approve,"
                        + " reject or withdraw it first");
        assertThatThrownBy(() -> service.propose(propose("E1060", TODAY.minusDays(1))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Valid until must be today (2026-10-06) or later");
        assertThatThrownBy(() -> service.propose(propose("E1060", TODAY.plusDays(91))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Valid until must be at most 90 days ahead, on or before 2027-01-04");
        assertThatThrownBy(() -> service.propose(propose("E9999", TODAY)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Employee E9999 is not on the staff register");
        assertThat(audited()).containsExactly(StaffArrearsOverrideService.PROPOSED);
        assertThat(audits().getFirst().getDetail()).isEqualTo("employee:E1060;validUntil:2027-01-04;reason:Repayment"
                + " plan agreed with Finance");
        assertThat(audits().getFirst().getChannelUsed()).isEqualTo("admin-portal");
    }

    @Test
    @DisplayName("a member who owes no written-off loan has nothing to override: an overdue loan must be repaid")
    void nothingToOverride() {
        owesWrittenOff = false;

        assertThatThrownBy(() -> service.propose(propose("E1060", TODAY)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Employee E1060 owes no written-off Staff Grocery Loan, so there is nothing to override."
                        + " An overdue loan cannot be overridden: it must be repaid first");
        assertThat(overrides).isEmpty();
    }

    @Test
    @DisplayName("the proposer cannot decide; a rejection needs a reason; approval replaces the earlier override, under"
            + " the register's lock")
    void decides() {
        StaffArrearsOverride earlier = approved(TODAY.plusDays(3));
        service.propose(propose("E1060", TODAY.plusDays(25)));
        long id = overrides.getLast().getId();

        as("CREDIT1");
        assertThatThrownBy(() -> service.decide(id, decision(StaffArrearsOverrideDecision.APPROVED, null)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("CREDIT1 proposed arrears override 2 and cannot also approve or reject it; another credit"
                        + " manager or SUPER_ADMIN must");
        as("credit2");
        assertThatThrownBy(() -> service.decide(id, decision(StaffArrearsOverrideDecision.REJECTED, " ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A reason is required to reject an arrears override");

        StaffArrearsOverrideResponse decided = service.decide(id, decision(StaffArrearsOverrideDecision.APPROVED,
                " Plan confirmed with Finance "));

        assertThat(decided.status()).isEqualTo(StaffArrearsOverrideStatus.APPROVED);
        assertThat(decided.decidedBy()).isEqualTo("credit2");
        assertThat(decided.decisionComment()).isEqualTo("Plan confirmed with Finance");
        assertThat(decided.inForce()).isTrue();
        assertThat(earlier.getStatus()).isEqualTo(StaffArrearsOverrideStatus.SUPERSEDED);
        assertThat(earlier.getSupersededBy()).isEqualTo(id);
        assertThat(earlier.getSupersededAt()).isEqualTo(NOW);
        verify(memberRepository, atLeastOnce()).lockRegister(StaffRegisterService.REGISTER_LOCK);
        assertThat(audited()).containsExactly(StaffArrearsOverrideService.PROPOSED,
                StaffArrearsOverrideService.SUPERSEDED, StaffArrearsOverrideService.APPROVED);
        assertThatThrownBy(() -> service.decide(id, decision(StaffArrearsOverrideDecision.REJECTED, "No")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Arrears override 2 is already approved");
        assertThatThrownBy(() -> service.decide(99L, decision(StaffArrearsOverrideDecision.REJECTED, "No")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Arrears override 99 not found");
    }

    @Test
    @DisplayName("an override past its last day, or for a member who no longer owes a written-off loan, cannot be"
            + " approved, but can be rejected")
    void approvalIsRefusedWhenItWouldBeEmpty() {
        StaffArrearsOverride lapsed = store(StaffArrearsOverride.builder().staffMemberId(6L).reason("Plan")
                .validUntil(TODAY.minusDays(1)).status(StaffArrearsOverrideStatus.PENDING).proposedBy("credit1")
                .proposedAt(NOW.minusDays(2)).build());
        as("credit2");

        assertThatThrownBy(() -> service.decide(lapsed.getId(), decision(StaffArrearsOverrideDecision.APPROVED,
                null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Arrears override 1's last day, 2026-10-05, has passed; reject it and propose a new one");

        lapsed.setValidUntil(TODAY);
        owesWrittenOff = false;
        assertThatThrownBy(() -> service.decide(lapsed.getId(), decision(StaffArrearsOverrideDecision.APPROVED,
                null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Employee E1060 owes no written-off Staff Grocery Loan, so arrears override 1 is not"
                        + " needed; reject it. An overdue loan cannot be overridden: it must be repaid first");
        assertThat(lapsed.getStatus()).isEqualTo(StaffArrearsOverrideStatus.PENDING);

        assertThat(service.decide(lapsed.getId(), decision(StaffArrearsOverrideDecision.REJECTED, "Settled"))
                .status()).isEqualTo(StaffArrearsOverrideStatus.REJECTED);
    }

    @Test
    @DisplayName("only the proposer withdraws, and only while pending")
    void withdraws() {
        service.propose(propose("E1060", TODAY.plusDays(25)));

        as("credit2");
        assertThatThrownBy(() -> service.withdraw(1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only credit1, who proposed arrears override 1, can withdraw it; anyone else approves or"
                        + " rejects it");
        as("credit1");
        StaffArrearsOverrideResponse withdrawn = service.withdraw(1L);

        assertThat(withdrawn.status()).isEqualTo(StaffArrearsOverrideStatus.WITHDRAWN);
        assertThat(withdrawn.decidedBy()).isEqualTo("credit1");
        assertThatThrownBy(() -> service.withdraw(1L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Arrears override 1 is already withdrawn");
        assertThat(audited()).containsExactly(StaffArrearsOverrideService.PROPOSED,
                StaffArrearsOverrideService.WITHDRAWN);
    }

    @Test
    @DisplayName("an approved override can be revoked with a reason; a used one cannot")
    void revokes() {
        StaffArrearsOverride inForce = approved(TODAY.plusDays(25));

        StaffArrearsOverrideResponse revoked = service.revoke(inForce.getId(), RevokeStaffArrearsOverrideRequest
                .builder().reason(" Plan not signed yet ").build());

        assertThat(revoked.status()).isEqualTo(StaffArrearsOverrideStatus.REVOKED);
        assertThat(revoked.revokedBy()).isEqualTo("credit1");
        assertThat(revoked.revokedAt()).isEqualTo(NOW);
        assertThat(revoked.revocationReason()).isEqualTo("Plan not signed yet");
        assertThat(revoked.inForce()).isNull();
        verify(memberRepository).lockRegister(StaffRegisterService.REGISTER_LOCK);

        StaffArrearsOverride used = approved(TODAY.plusDays(25));
        used.setStatus(StaffArrearsOverrideStatus.USED);
        assertThatThrownBy(() -> service.revoke(used.getId(), RevokeStaffArrearsOverrideRequest.builder()
                .reason("Too late").build()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Arrears override 2 is used, so there is nothing to revoke");
        assertThat(audited()).containsExactly(StaffArrearsOverrideService.REVOKED);
    }

    @Test
    @DisplayName("the loan accepted under an override uses it up, as the borrower, from the SuperApp")
    void use() {
        StaffArrearsOverride inForce = approved(TODAY);
        StaffLoan loan = StaffLoan.builder().id(158L).reference("SGL-2026-000158").employeeNumber("E1060").build();

        service.use(inForce, loan, "borrower:E1060");

        assertThat(inForce.getStatus()).isEqualTo(StaffArrearsOverrideStatus.USED);
        assertThat(inForce.getStaffLoanId()).isEqualTo(158L);
        assertThat(inForce.getUsedAt()).isEqualTo(NOW);
        assertThat(audits()).singleElement().satisfies(audit -> {
            assertThat(audit.getEventType()).isEqualTo(StaffArrearsOverrideService.USED);
            assertThat(audit.getActorId()).isEqualTo("borrower:E1060");
            assertThat(audit.getChannelUsed()).isEqualTo("superapp");
            assertThat(audit.getDetail()).isEqualTo("employee:E1060;loan:SGL-2026-000158");
        });

        StaffArrearsOverride lapsed = approved(TODAY.minusDays(1));
        assertThatThrownBy(() -> service.use(lapsed, loan, "borrower:E1060"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lapsed.getStatus()).isEqualTo(StaffArrearsOverrideStatus.APPROVED);
    }

    @Test
    @DisplayName("the list names the member and, for a used override, the loan taken under it")
    @SuppressWarnings("unchecked")
    void lists() {
        StaffArrearsOverride used = approved(TODAY);
        used.setStatus(StaffArrearsOverrideStatus.USED);
        used.setStaffLoanId(158L);
        used.setUsedAt(NOW);
        StaffArrearsOverride inForce = approved(TODAY.plusDays(25));
        loans.add(StaffLoan.builder().id(158L).reference("SGL-2026-000158").build());
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(i ->
                new PageImpl<>(List.of(inForce, used), (Pageable) i.getArgument(1), 2));

        List<StaffArrearsOverrideResponse> listed = service.overrides(null, null, PageRequest.of(0, 20))
                .getContent();

        assertThat(listed).extracting(StaffArrearsOverrideResponse::fullName).containsOnly("Tatenda Mhlanga");
        assertThat(listed.getFirst().staffLoanReference()).isNull();
        assertThat(listed.getFirst().inForce()).isTrue();
        assertThat(listed.getLast().staffLoanReference()).isEqualTo("SGL-2026-000158");
        assertThat(listed.getLast().inForce()).isNull();
        verify(repository).findAll(any(Specification.class), eq(PageRequest.of(0, 20,
                Sort.by(Sort.Direction.DESC, "id"))));
    }
}
