package zw.co.innbucks.loans.core.staff.offer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.*;

/**
 * Credit's limit overrides under maker-checker (FR-SGL-011): who may propose, decide, withdraw and revoke, what
 * approving replaces, and that a limit of 0 takes the member's open offer back at once. In-memory repositories.
 */
class StaffLimitOverrideServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 7, 12, 30);

    private final List<StaffLimitOverride> overrides = new ArrayList<>();
    private StaffMember member;
    private StaffOffer openOffer;
    private StaffMemberRepository memberRepository;
    private StaffOfferRepository offerRepository;
    private AuthService authService;
    private AuditService auditService;
    private StaffLimitOverrideService service;

    @BeforeEach
    void setUp() {
        member = StaffMember.builder().id(2L).employeeNumber("E1012").fullName("Chipo Banda").grade("C4")
                .employmentStatus(StaffEmploymentStatus.ACTIVE).build();
        memberRepository = mock(StaffMemberRepository.class);
        when(memberRepository.findByEmployeeNumber(any())).thenAnswer(i -> "E1012".equals(i.getArgument(0))
                ? Optional.of(member) : Optional.empty());
        when(memberRepository.findById(2L)).thenAnswer(i -> Optional.of(member));

        StaffLimitOverrideRepository repository = mock(StaffLimitOverrideRepository.class);
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

        openOffer = StaffOffer.builder().id(9L).staffMemberId(2L).runId(1L).cycleStart(LocalDate.of(2026, 10, 5))
                .grade("C4").scoreBand("Band C").gradeLimitChangeId(1L).amount(new BigDecimal("300.00"))
                .issuedAt(NOW.minusDays(1)).expiresAt(NOW.plusDays(6)).status(StaffOfferStatus.ACTIVE).build();
        offerRepository = mock(StaffOfferRepository.class);
        when(offerRepository.findByStaffMemberIdAndStatus(2L, StaffOfferStatus.ACTIVE))
                .thenAnswer(i -> Optional.of(openOffer));

        authService = mock(AuthService.class);
        as("credit1");
        auditService = mock(AuditService.class);
        service = new StaffLimitOverrideService(repository, memberRepository, offerRepository, authService,
                auditService, new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-10-06T07:12:30Z"),
                ZoneOffset.UTC)));
    }

    private StaffLimitOverride store(StaffLimitOverride override) {
        if (override.getId() == null) {
            override.setId((long) overrides.size() + 1);
            overrides.add(override);
        }
        return override;
    }

    private void as(String username) {
        when(authService.getLoggedInUsername()).thenReturn(username);
    }

    private static ProposeStaffLimitOverrideRequest propose(String amount) {
        return ProposeStaffLimitOverrideRequest.builder().employeeNumber(" e1012 ").amount(new BigDecimal(amount))
                .reason(" Existing salary advance outstanding ").build();
    }

    private static StaffLimitOverrideDecisionRequest decision(StaffLimitOverrideDecision decision, String comment) {
        return StaffLimitOverrideDecisionRequest.builder().decision(decision).comment(comment).build();
    }

    private List<String> audited() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(builder -> builder.build().getEventType()).toList();
    }

    @Test
    @DisplayName("a proposal is set for the member's grade; one per member may wait; an unknown employee is a 404")
    void proposes() {
        StaffLimitOverrideResponse proposed = service.propose(propose("150.00"));

        assertThat(proposed.status()).isEqualTo(StaffLimitOverrideStatus.PENDING);
        assertThat(proposed.employeeNumber()).isEqualTo("E1012");
        assertThat(proposed.grade()).isEqualTo("C4");
        assertThat(proposed.reason()).isEqualTo("Existing salary advance outstanding");
        assertThat(proposed.proposedAt()).isEqualTo(NOW);
        assertThat(proposed.amount()).as("stored to the cent").hasToString("150.00");
        assertThat(proposed.inForce()).as("only an approved override is in force or not").isNull();
        assertThatThrownBy(() -> service.propose(propose("100.00")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Employee E1012 already has a limit override waiting for a decision (1); approve, reject"
                        + " or withdraw it first");
        assertThatThrownBy(() -> service.propose(ProposeStaffLimitOverrideRequest.builder().employeeNumber("E9999")
                .amount(BigDecimal.TEN).reason("x").build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Employee E9999 is not on the staff register");
        assertThat(audited()).containsExactly(StaffLimitOverrideService.PROPOSED);
    }

    @Test
    @DisplayName("the proposer cannot decide; a rejection needs a reason; approval replaces the earlier override")
    void decides() {
        Long first = service.propose(propose("150.00")).id();
        as("credit2");
        service.decide(first, decision(StaffLimitOverrideDecision.APPROVED, null));
        as("credit2");
        Long second = service.propose(propose("100.00")).id();

        assertThatThrownBy(() -> service.decide(second, decision(StaffLimitOverrideDecision.APPROVED, null)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("credit2 proposed limit override 2 and cannot also approve or reject it; another credit"
                        + " manager or SUPER_ADMIN must");
        as("CREDIT1");
        assertThatThrownBy(() -> service.decide(second, decision(StaffLimitOverrideDecision.REJECTED, " ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A reason is required to reject a limit override");

        StaffLimitOverrideResponse approved = service.decide(second, decision(StaffLimitOverrideDecision.APPROVED,
                "Confirmed with Payroll"));

        assertThat(approved.status()).isEqualTo(StaffLimitOverrideStatus.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("CREDIT1");
        assertThat(approved.decisionComment()).isEqualTo("Confirmed with Payroll");
        assertThat(approved.inForce()).isTrue();
        assertThat(overrides.getFirst().getStatus()).isEqualTo(StaffLimitOverrideStatus.SUPERSEDED);
        assertThat(overrides.getFirst().getSupersededBy()).isEqualTo(second);
        assertThat(openOffer.getStatus()).as("a lower limit waits for the next weekly offer")
                .isEqualTo(StaffOfferStatus.ACTIVE);
        verify(memberRepository, atLeastOnce()).lockRegister(StaffRegisterService.REGISTER_LOCK);
        assertThat(audited()).containsExactly(StaffLimitOverrideService.PROPOSED, StaffLimitOverrideService.APPROVED,
                StaffLimitOverrideService.PROPOSED, StaffLimitOverrideService.SUPERSEDED,
                StaffLimitOverrideService.APPROVED);
        assertThatThrownBy(() -> service.decide(second, decision(StaffLimitOverrideDecision.REJECTED, "no")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Limit override 2 is already approved");
    }

    @Test
    @DisplayName("approving a limit of 0 takes the member's open offer back at once")
    void zeroWithdrawsTheOffer() {
        Long id = service.propose(propose("0")).id();
        as("credit2");

        service.decide(id, decision(StaffLimitOverrideDecision.APPROVED, null));

        assertThat(openOffer.getStatus()).isEqualTo(StaffOfferStatus.WITHDRAWN);
        assertThat(openOffer.getClosedAt()).isEqualTo(NOW);
        assertThat(openOffer.getClosedReason()).isEqualTo("Credit set their limit to 0 (limit override 1)");
        verify(offerRepository).save(openOffer);
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, times(2)).record(captor.capture());
        assertThat(captor.getValue().build().getDetail())
                .isEqualTo("employee:E1012;grade:C4;amount:0.00;withdrewOffer:9");
    }

    @Test
    @DisplayName("an override proposed for a grade the member no longer holds cannot be approved, and is not in force")
    void gradeChanged() {
        Long id = service.propose(propose("150.00")).id();
        member.setGrade("C5");
        as("credit2");

        assertThatThrownBy(() -> service.decide(id, decision(StaffLimitOverrideDecision.APPROVED, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Employee E1012's grade has changed from C4 to C5 since limit override 1 was proposed;"
                        + " reject it and propose one for the new grade");
        assertThat(service.decide(id, decision(StaffLimitOverrideDecision.REJECTED, "Regraded")).status())
                .as("it can still be rejected").isEqualTo(StaffLimitOverrideStatus.REJECTED);

        StaffLimitOverride approved = overrides.getFirst().toBuilder().status(StaffLimitOverrideStatus.APPROVED)
                .build();
        StaffLimitOverrideResponse read = StaffLimitOverrideResponse.of(approved, member);
        assertThat(read.inForce()).isFalse();
        assertThat(read.notInForceReason()).isEqualTo("Employee E1012's grade is now C5; this override was set for C4");
    }

    @Test
    @DisplayName("only the proposer withdraws a pending override; Credit revokes an approved one with a reason")
    void withdrawAndRevoke() {
        Long pending = service.propose(propose("150.00")).id();
        as("credit2");
        assertThatThrownBy(() -> service.withdraw(pending)).isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only credit1, who proposed limit override 1, can withdraw it; anyone else approves or"
                        + " rejects it");
        as("credit1");
        assertThat(service.withdraw(pending).status()).isEqualTo(StaffLimitOverrideStatus.WITHDRAWN);
        assertThat(overrides.getFirst().getDecidedBy()).isEqualTo("credit1");

        Long approved = service.propose(propose("150.00")).id();
        as("credit2");
        service.decide(approved, decision(StaffLimitOverrideDecision.APPROVED, null));
        StaffLimitOverrideResponse revoked = service.revoke(approved, RevokeStaffLimitOverrideRequest.builder()
                .reason(" Salary advance repaid ").build());

        assertThat(revoked.status()).isEqualTo(StaffLimitOverrideStatus.REVOKED);
        assertThat(revoked.revokedBy()).isEqualTo("credit2");
        assertThat(revoked.revokedAt()).isEqualTo(NOW);
        assertThat(revoked.revocationReason()).isEqualTo("Salary advance repaid");
        assertThatThrownBy(() -> service.revoke(pending, RevokeStaffLimitOverrideRequest.builder().reason("x")
                .build()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Limit override 1 is withdrawn, so there is nothing to revoke");
        assertThatThrownBy(() -> service.revoke(99L, RevokeStaffLimitOverrideRequest.builder().reason("x").build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Limit override 99 not found");
        assertThat(audited()).endsWith(StaffLimitOverrideService.REVOKED);
    }
}
