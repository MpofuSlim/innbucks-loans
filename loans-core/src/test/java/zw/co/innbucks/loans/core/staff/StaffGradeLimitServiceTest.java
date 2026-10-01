package zw.co.innbucks.loans.core.staff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The Staff Grocery Loan grade-to-limit matrix (FR-SGL-009, FR-SGL-010): changes are proposed by one person and decided
 * by another, apply from today or later, never rewrite a limit in force, and resolve to one limit per grade per day.
 * Today is 1 October 2026 in Harare.
 */
class StaffGradeLimitServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    private final List<StaffGradeLimitChange> stored = new ArrayList<>();
    private StaffGradeLimitChangeRepository repository;
    private AuthService authService;
    private AuditService auditService;
    private StaffGradeLimitService service;

    @BeforeEach
    void setUp() {
        repository = mock(StaffGradeLimitChangeRepository.class);
        when(repository.findAll()).thenAnswer(i -> List.copyOf(stored));
        when(repository.findByIdForUpdate(anyLong())).thenAnswer(i -> byId(i.getArgument(0)));
        when(repository.findByStatusIn(any())).thenAnswer(i -> {
            Collection<StaffGradeLimitChangeStatus> statuses = i.getArgument(0);
            return stored.stream().filter(change -> statuses.contains(change.getStatus())).toList();
        });
        when(repository.findByGradeAndEffectiveFromAndStatus(any(), any(), any()))
                .thenAnswer(i -> find(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        when(repository.findForUpdate(any(), any(), any()))
                .thenAnswer(i -> find(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        when(repository.existsByGradeAndStatus(any(), any())).thenAnswer(i -> stored.stream()
                .anyMatch(change -> change.getGrade().equals(i.getArgument(0))
                        && change.getStatus() == i.getArgument(1)));
        when(repository.findFirstByGradeAndStatusAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any(), any(),
                any())).thenAnswer(i -> stored.stream()
                .filter(change -> change.getGrade().equals(i.getArgument(0)) && change.getStatus() == i.getArgument(1))
                .filter(change -> !change.getEffectiveFrom().isAfter(i.getArgument(2)))
                .max(Comparator.comparing(StaffGradeLimitChange::getEffectiveFrom)));
        when(repository.save(any())).thenAnswer(i -> keep(i.getArgument(0)));
        when(repository.saveAndFlush(any())).thenAnswer(i -> keep(i.getArgument(0)));
        authService = mock(AuthService.class);
        as("credit1");
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"), ZoneOffset.UTC);
        service = new StaffGradeLimitService(repository, authService, auditService, new MarketTimeZone("ZW", clock));
    }

    private Optional<StaffGradeLimitChange> byId(Long id) {
        return stored.stream().filter(change -> change.getId().equals(id)).findFirst();
    }

    private Optional<StaffGradeLimitChange> find(String grade, LocalDate from, StaffGradeLimitChangeStatus status) {
        return stored.stream()
                .filter(change -> change.getGrade().equals(grade) && change.getEffectiveFrom().equals(from)
                        && change.getStatus() == status)
                .findFirst();
    }

    private StaffGradeLimitChange keep(StaffGradeLimitChange change) {
        if (change.getId() == null) {
            change.setId((long) stored.size() + 1);
            stored.add(change);
        }
        return change;
    }

    private void as(String username) {
        when(authService.getLoggedInUsername()).thenReturn(username);
    }

    private static ProposeStaffGradeLimitRequest proposal(String grade, String limit, LocalDate from) {
        return ProposeStaffGradeLimitRequest.builder().grade(grade).scoreBand("Band C")
                .maximumLimit(new BigDecimal(limit)).effectiveFrom(from).comment("Annual review").build();
    }

    private static StaffGradeLimitDecisionRequest decision(StaffGradeLimitDecision decision, String comment) {
        return StaffGradeLimitDecisionRequest.builder().decision(decision).comment(comment).build();
    }

    /** An approved limit already in the matrix, as if proposed by credit1 and approved by credit2. */
    private StaffGradeLimitChange approved(String grade, String limit, LocalDate from) {
        return keep(StaffGradeLimitChange.builder()
                .grade(grade).scoreBand("Band C").maximumLimit(new BigDecimal(limit)).effectiveFrom(from)
                .status(StaffGradeLimitChangeStatus.APPROVED)
                .proposedBy("credit1").proposedAt(LocalDateTime.of(2026, 9, 30, 7, 0))
                .decidedBy("credit2").decidedAt(LocalDateTime.of(2026, 9, 30, 8, 0))
                .build());
    }

    private List<String> auditedEvents() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(builder -> builder.build().getEventType()).toList();
    }

    @Test
    @DisplayName("a proposal waits as PENDING, normalised, until someone else decides it")
    void proposalWaits() {
        StaffGradeLimitChangeResponse proposed = service.propose(proposal(" c4 ", "350", TODAY.plusMonths(1)));

        assertThat(proposed.status()).isEqualTo(StaffGradeLimitChangeStatus.PENDING);
        assertThat(proposed.grade()).isEqualTo("C4");
        assertThat(proposed.maximumLimit()).isEqualByComparingTo("350.00");
        assertThat(proposed.maximumLimit().scale()).isEqualTo(2);
        assertThat(proposed.proposedBy()).isEqualTo("credit1");
        assertThat(proposed.decidedBy()).isNull();
        assertThat(auditedEvents()).containsExactly(StaffGradeLimitService.PROPOSED);
        assertThat(service.limitOn("C4", TODAY.plusMonths(2))).isEmpty();
    }

    @Test
    @DisplayName("a pending change shows the limit it would replace on its date")
    void pendingChangeShowsWhatItReplaces() {
        approved("C4", "300.00", TODAY);

        StaffGradeLimitChangeResponse proposed = service.propose(proposal("C4", "350", TODAY.plusMonths(1)));

        assertThat(proposed.replacing()).isNotNull();
        assertThat(proposed.replacing().maximumLimit()).isEqualByComparingTo("300.00");
        assertThat(proposed.replacing().effectiveFrom()).isEqualTo(TODAY);
        assertThat(service.changes("c4", StaffGradeLimitChangeStatus.PENDING))
                .singleElement()
                .satisfies(change -> assertThat(change.replacing().changeId()).isEqualTo(1L));
    }

    @Test
    @DisplayName("a limit cannot apply from a day that has passed; today is the earliest")
    void noPastDates() {
        assertThatThrownBy(() -> service.propose(proposal("C4", "350", TODAY.minusDays(1))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A grade limit cannot apply from 2026-09-30, which has passed; the earliest is today,"
                        + " 2026-10-01");

        assertThat(service.propose(proposal("C4", "350", TODAY)).effectiveFrom()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("one proposal per grade and date waits at a time")
    void onePendingPerGradeAndDate() {
        service.propose(proposal("C4", "350", TODAY.plusMonths(1)));

        assertThatThrownBy(() -> service.propose(proposal("C4", "360", TODAY.plusMonths(1))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A change to grade C4 from 2026-11-01 is already waiting for approval (change 1);"
                        + " approve, reject or withdraw it first");
        assertThat(service.propose(proposal("C4", "360", TODAY.plusMonths(2))).status())
                .isEqualTo(StaffGradeLimitChangeStatus.PENDING);
    }

    @Test
    @DisplayName("losing the race to propose for the same grade and date is a 409, not a 500")
    void proposalRaceIsAConflict() {
        doThrow(new DataIntegrityViolationException("uq_staff_grade_limit_changes_pending"))
                .when(repository).saveAndFlush(any());

        assertThatThrownBy(() -> service.propose(proposal("C4", "350", TODAY.plusMonths(1))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A change to grade C4 from 2026-11-01 is already waiting for approval; approve, reject or"
                        + " withdraw it first");
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("a limit in force is never rewritten: proposing over it is refused")
    void limitInForceIsNotRewritten() {
        approved("C4", "300.00", TODAY);

        assertThatThrownBy(() -> service.propose(proposal("C4", "350", TODAY)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Grade C4's limit from 2026-10-01 is already in force and cannot be replaced; propose a"
                        + " change from a later day");
    }

    @Test
    @DisplayName("the proposer cannot approve or reject their own change; someone else can")
    void makerIsNotChecker() {
        Long id = service.propose(proposal("C4", "300", TODAY)).id();

        for (StaffGradeLimitDecision outcome : StaffGradeLimitDecision.values()) {
            assertThatThrownBy(() -> service.decide(id, decision(outcome, "Fine")))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("credit1 proposed grade limit change 1 and cannot also approve or reject it; another"
                            + " credit manager or SUPER_ADMIN must");
        }
        as("CREDIT1");
        assertThatThrownBy(() -> service.decide(id, decision(StaffGradeLimitDecision.APPROVED, null)))
                .as("the same person whatever the case").isInstanceOf(AccessDeniedException.class);

        as("credit2");
        StaffGradeLimitChangeResponse decided = service.decide(id, decision(StaffGradeLimitDecision.APPROVED,
                " Matches the signed-off matrix "));

        assertThat(decided.status()).isEqualTo(StaffGradeLimitChangeStatus.APPROVED);
        assertThat(decided.decidedBy()).isEqualTo("credit2");
        assertThat(decided.decisionComment()).isEqualTo("Matches the signed-off matrix");
        assertThat(service.limitOn("c4", TODAY)).hasValueSatisfying(limit -> {
            assertThat(limit.maximumLimit()).isEqualByComparingTo("300.00");
            assertThat(limit.approvedBy()).isEqualTo("credit2");
            assertThat(limit.lends()).isTrue();
        });
        assertThat(service.recognises("c4")).isTrue();
        assertThat(auditedEvents()).containsExactly(StaffGradeLimitService.PROPOSED, StaffGradeLimitService.APPROVED);
    }

    @Test
    @DisplayName("a rejection needs a reason and puts nothing in the matrix")
    void rejectionNeedsAReason() {
        Long id = service.propose(proposal("C4", "300", TODAY)).id();
        as("credit2");

        assertThatThrownBy(() -> service.decide(id, decision(StaffGradeLimitDecision.REJECTED, "  ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A reason is required to reject a grade limit change");
        StaffGradeLimitChangeResponse rejected = service.decide(id,
                decision(StaffGradeLimitDecision.REJECTED, "Not the signed-off figure"));

        assertThat(rejected.status()).isEqualTo(StaffGradeLimitChangeStatus.REJECTED);
        assertThat(rejected.decisionComment()).isEqualTo("Not the signed-off figure");
        assertThat(service.limitOn("C4", TODAY)).isEmpty();
        assertThat(service.recognises("C4")).isFalse();
    }

    @Test
    @DisplayName("a decided change is never decided again, and an unknown one is a 404")
    void decidedOnce() {
        Long id = service.propose(proposal("C4", "300", TODAY)).id();
        as("credit2");
        service.decide(id, decision(StaffGradeLimitDecision.APPROVED, null));

        assertThatThrownBy(() -> service.decide(id, decision(StaffGradeLimitDecision.REJECTED, "Changed my mind")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Grade limit change 1 is already approved");
        assertThatThrownBy(() -> service.decide(99L, decision(StaffGradeLimitDecision.APPROVED, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Grade limit change 99 not found");
    }

    @Test
    @DisplayName("a proposal whose date passed while it waited cannot be approved, only rejected")
    void staleProposalIsNotApproved() {
        StaffGradeLimitChange waiting = keep(StaffGradeLimitChange.builder()
                .grade("C4").scoreBand("Band C").maximumLimit(new BigDecimal("300.00"))
                .effectiveFrom(TODAY.minusDays(2)).status(StaffGradeLimitChangeStatus.PENDING)
                .proposedBy("credit1").proposedAt(LocalDateTime.of(2026, 9, 28, 7, 0)).build());
        as("credit2");

        assertThatThrownBy(() -> service.decide(waiting.getId(), decision(StaffGradeLimitDecision.APPROVED, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Grade limit change 1 was to apply from 2026-09-29, which has passed; reject it and"
                        + " propose it again from today or later");
        assertThat(service.decide(waiting.getId(), decision(StaffGradeLimitDecision.REJECTED, "Stale")).status())
                .isEqualTo(StaffGradeLimitChangeStatus.REJECTED);
    }

    @Test
    @DisplayName("approving over a limit still to come supersedes it; the matrix keeps one limit per day")
    void futureLimitIsSuperseded() {
        StaffGradeLimitChange scheduled = approved("C4", "350.00", TODAY.plusMonths(1));
        Long id = service.propose(proposal("C4", "375", TODAY.plusMonths(1))).id();
        as("credit2");

        StaffGradeLimitChangeResponse decided = service.decide(id, decision(StaffGradeLimitDecision.APPROVED, null));

        assertThat(decided.status()).isEqualTo(StaffGradeLimitChangeStatus.APPROVED);
        assertThat(scheduled.getStatus()).isEqualTo(StaffGradeLimitChangeStatus.SUPERSEDED);
        assertThat(scheduled.getSupersededBy()).isEqualTo(id);
        assertThat(scheduled.getSupersededAt()).isNotNull();
        verify(repository).saveAndFlush(scheduled);
        assertThat(service.limitOn("C4", TODAY.plusMonths(1)))
                .hasValueSatisfying(limit -> assertThat(limit.maximumLimit()).isEqualByComparingTo("375.00"));
        assertThat(auditedEvents()).containsExactly(StaffGradeLimitService.PROPOSED,
                StaffGradeLimitService.SUPERSEDED, StaffGradeLimitService.APPROVED);
    }

    @Test
    @DisplayName("a limit that came into force while a proposal for its day waited is not replaced by approving it")
    void limitThatCameIntoForceIsNotReplaced() {
        StaffGradeLimitChange waiting = keep(StaffGradeLimitChange.builder()
                .grade("C4").scoreBand("Band C").maximumLimit(new BigDecimal("375.00")).effectiveFrom(TODAY)
                .status(StaffGradeLimitChangeStatus.PENDING)
                .proposedBy("credit1").proposedAt(LocalDateTime.of(2026, 9, 29, 7, 0)).build());
        StaffGradeLimitChange inForce = approved("C4", "350.00", TODAY);
        as("credit2");

        assertThatThrownBy(() -> service.decide(waiting.getId(), decision(StaffGradeLimitDecision.APPROVED, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Grade C4's limit from 2026-10-01 is already in force and cannot be replaced; propose a"
                        + " change from a later day");
        assertThat(inForce.getStatus()).isEqualTo(StaffGradeLimitChangeStatus.APPROVED);
    }

    @Test
    @DisplayName("only the proposer withdraws a pending change")
    void onlyTheProposerWithdraws() {
        Long id = service.propose(proposal("C4", "300", TODAY)).id();
        as("credit2");

        assertThatThrownBy(() -> service.withdraw(id))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only credit1, who proposed grade limit change 1, can withdraw it; anyone else approves or"
                        + " rejects it");
        as("credit1");
        StaffGradeLimitChangeResponse withdrawn = service.withdraw(id);

        assertThat(withdrawn.status()).isEqualTo(StaffGradeLimitChangeStatus.WITHDRAWN);
        assertThat(withdrawn.decidedBy()).isEqualTo("credit1");
        assertThatThrownBy(() -> service.withdraw(id))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Grade limit change 1 is already withdrawn");
    }

    @Test
    @DisplayName("the matrix resolves each grade's limit on the day, lists limits to come and counts proposals")
    void matrixOnADay() {
        approved("C4", "300.00", TODAY.minusMonths(1));
        approved("C4", "320.00", TODAY);
        approved("C4", "350.00", TODAY.plusMonths(1));
        approved("D1", "500.00", TODAY.plusDays(5));
        keep(StaffGradeLimitChange.builder()
                .grade("B2").scoreBand("Band B").maximumLimit(new BigDecimal("150.00")).effectiveFrom(TODAY)
                .status(StaffGradeLimitChangeStatus.PENDING)
                .proposedBy("credit1").proposedAt(LocalDateTime.of(2026, 9, 30, 7, 0)).build());
        keep(StaffGradeLimitChange.builder()
                .grade("E1").scoreBand("Band E").maximumLimit(new BigDecimal("900.00")).effectiveFrom(TODAY)
                .status(StaffGradeLimitChangeStatus.REJECTED).proposedBy("credit1")
                .proposedAt(LocalDateTime.of(2026, 9, 30, 7, 0)).decidedBy("credit2")
                .decidedAt(LocalDateTime.of(2026, 9, 30, 8, 0)).build());

        List<StaffGradeLimitResponse> today = service.matrix(null);

        assertThat(today).extracting(StaffGradeLimitResponse::grade).containsExactly("B2", "C4", "D1");
        StaffGradeLimitResponse b2 = today.get(0);
        assertThat(b2.current()).isNull();
        assertThat(b2.scheduled()).isEmpty();
        assertThat(b2.pendingChanges()).isEqualTo(1);
        StaffGradeLimitResponse c4 = today.get(1);
        assertThat(c4.current().maximumLimit()).isEqualByComparingTo("320.00");
        assertThat(c4.scheduled()).extracting(StaffGradeLimit::maximumLimit)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("350.00"));
        StaffGradeLimitResponse d1 = today.get(2);
        assertThat(d1.current()).isNull();
        assertThat(d1.scheduled()).hasSize(1);

        List<StaffGradeLimitResponse> lastMonth = service.matrix(TODAY.minusDays(1));
        assertThat(lastMonth.get(1).current().maximumLimit()).isEqualByComparingTo("300.00");
        assertThat(lastMonth.get(1).scheduled()).hasSize(2);
        assertThat(service.limitOn("D1", TODAY)).isEmpty();
        assertThat(service.recognises("D1")).as("approved, though not yet in force").isTrue();
        assertThat(service.recognises("E1")).as("only rejected").isFalse();
        assertThat(service.recognises(" ")).isFalse();
    }

    @Test
    @DisplayName("a zero limit is a limit that stops lending to the grade")
    void zeroStopsLending() {
        approved("A1", "0.00", TODAY);

        assertThat(service.limitOn("A1", TODAY)).hasValueSatisfying(limit -> assertThat(limit.lends()).isFalse());
        assertThat(service.recognises("A1")).isTrue();
    }

    @Test
    @DisplayName("changes list newest first, filtered by grade and status")
    void changesNewestFirst() {
        approved("C4", "300.00", TODAY);
        approved("D1", "500.00", TODAY);
        service.propose(proposal("C4", "350", TODAY.plusMonths(1)));

        assertThat(service.changes(null, null)).extracting(StaffGradeLimitChangeResponse::id)
                .containsExactly(3L, 2L, 1L);
        assertThat(service.changes("c4", null)).extracting(StaffGradeLimitChangeResponse::id)
                .containsExactly(3L, 1L);
        assertThat(service.changes(null, StaffGradeLimitChangeStatus.APPROVED))
                .extracting(StaffGradeLimitChangeResponse::id).containsExactly(2L, 1L);
        assertThat(service.changes(null, StaffGradeLimitChangeStatus.APPROVED))
                .allSatisfy(change -> assertThat(change.replacing()).isNull());
    }
}
