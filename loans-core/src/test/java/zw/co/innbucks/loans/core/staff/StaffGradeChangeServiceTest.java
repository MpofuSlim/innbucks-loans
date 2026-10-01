package zw.co.innbucks.loans.core.staff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverride;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideStatus;

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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retiring and renaming a grade (FR-SGL-010): proposed by one person, decided by another, refused while anything would
 * land on a grade that is gone, and carried out whole. The matrix holds DRIVER/OFFICE ORDERLY at 50.00 from 1 September
 * and 60.00 from 1 November, and ANALYST at 70.00. Today is 1 October 2026 in Harare.
 */
class StaffGradeChangeServiceTest {

    private static final String DRIVER = "DRIVER/OFFICE ORDERLY";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 8, 0);
    private static final LocalDateTime MEMBER_UPDATED = LocalDateTime.of(2026, 9, 15, 7, 0);

    private final List<StaffGradeChange> changes = new ArrayList<>();
    private final List<StaffGradeLimitChange> limits = new ArrayList<>();
    private final List<StaffMember> members = new ArrayList<>();
    private final List<StaffMemberChange> history = new ArrayList<>();
    private final List<StaffLimitOverride> overrides = new ArrayList<>();
    private final List<Long> pendingBatches = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private final AuthService authService = mock(AuthService.class);
    private final AuditService auditService = mock(AuditService.class);
    private StaffGradeChangeService service;

    @BeforeEach
    void setUp() {
        StaffGradeChangeRepository repository = mock(StaffGradeChangeRepository.class);
        when(repository.findAllByOrderByIdDesc()).thenAnswer(i -> changes.stream()
                .sorted(Comparator.comparing(StaffGradeChange::getId).reversed()).toList());
        when(repository.findByIdForUpdate(anyLong())).thenAnswer(i -> changes.stream()
                .filter(change -> change.getId().equals(i.getArgument(0))).findFirst());
        when(repository.findNaming(any(), any())).thenAnswer(i -> changes.stream()
                .filter(change -> change.getStatus() == i.getArgument(1))
                .filter(change -> change.getGrade().equals(i.getArgument(0))
                        || i.getArgument(0).equals(change.getNewGrade())).toList());
        when(repository.saveAndFlush(any())).thenAnswer(i -> keepChange(i.getArgument(0)));
        when(repository.save(any())).thenAnswer(i -> keepChange(i.getArgument(0)));

        StaffGradeLimitChangeRepository limitRepository = mock(StaffGradeLimitChangeRepository.class);
        when(limitRepository.existsByGradeAndStatus(any(), any())).thenAnswer(i -> limits.stream()
                .anyMatch(limit -> limit.getGrade().equals(i.getArgument(0)) && limit.getStatus() == i.getArgument(1)));
        when(limitRepository.findByGradeAndStatusOrderByEffectiveFrom(any(), any())).thenAnswer(i -> limits.stream()
                .filter(limit -> limit.getGrade().equals(i.getArgument(0)) && limit.getStatus() == i.getArgument(1))
                .sorted(Comparator.comparing(StaffGradeLimitChange::getEffectiveFrom)).toList());
        when(limitRepository.saveAllAndFlush(any())).thenAnswer(i -> {
            Collection<StaffGradeLimitChange> saved = i.getArgument(0);
            saved.forEach(this::keepLimit);
            return List.copyOf(saved);
        });

        StaffMemberRepository memberRepository = mock(StaffMemberRepository.class);
        when(memberRepository.findByGradeForUpdate(any())).thenAnswer(i -> members.stream()
                .filter(member -> member.getGrade().equals(i.getArgument(0))).toList());
        when(memberRepository.countByGrade(any())).thenAnswer(i -> members.stream()
                .filter(member -> member.getGrade().equals(i.getArgument(0))).count());
        when(memberRepository.countByGradeAndEmploymentStatusIn(any(), any())).thenAnswer(i -> {
            Collection<StaffEmploymentStatus> statuses = i.getArgument(1);
            return members.stream().filter(member -> member.getGrade().equals(i.getArgument(0)))
                    .filter(member -> statuses.contains(member.getEmploymentStatus())).count();
        });

        StaffMemberChangeRepository memberChangeRepository = mock(StaffMemberChangeRepository.class);
        when(memberChangeRepository.saveAll(any())).thenAnswer(i -> {
            Iterable<StaffMemberChange> saved = i.getArgument(0);
            saved.forEach(history::add);
            return history;
        });

        StaffRegisterRowRepository rowRepository = mock(StaffRegisterRowRepository.class);
        when(rowRepository.findPendingBatchesStaging(any())).thenAnswer(i -> List.copyOf(pendingBatches));

        StaffLimitOverrideRepository overrideRepository = mock(StaffLimitOverrideRepository.class);
        when(overrideRepository.findByGradeForUpdate(any(), any())).thenAnswer(i -> {
            Collection<StaffLimitOverrideStatus> statuses = i.getArgument(1);
            return overrides.stream().filter(override -> override.getGrade().equals(i.getArgument(0)))
                    .filter(override -> statuses.contains(override.getStatus())).toList();
        });

        as("credit1");
        service = new StaffGradeChangeService(repository, limitRepository, memberRepository, memberChangeRepository,
                rowRepository, overrideRepository, authService, auditService, new MarketTimeZone("ZW",
                Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"), ZoneOffset.UTC)));

        approvedLimit(DRIVER, "50.00", LocalDate.of(2026, 9, 1));
        approvedLimit(DRIVER, "60.00", LocalDate.of(2026, 11, 1));
        approvedLimit("ANALYST", "70.00", LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("a rename carries the grade over whole: limits with their dates, staff with their history, overrides")
    void rename() {
        StaffMember active = member(1L, "E1001", StaffEmploymentStatus.ACTIVE);
        StaffMember resigned = member(2L, "E1002", StaffEmploymentStatus.RESIGNED);
        StaffLimitOverride approved = override(11L, 1L, StaffLimitOverrideStatus.APPROVED);
        StaffLimitOverride pending = override(12L, 2L, StaffLimitOverrideStatus.PENDING);
        StaffLimitOverride revoked = override(13L, 1L, StaffLimitOverrideStatus.REVOKED);

        StaffGradeChangeResponse proposed = service.propose(rename(" driver / office  orderly", "Driver / Orderly"));

        assertThat(proposed.status()).isEqualTo(StaffGradeChangeStatus.PENDING);
        assertThat(proposed.grade()).isEqualTo(DRIVER);
        assertThat(proposed.newGrade()).isEqualTo("DRIVER/ORDERLY");
        assertThat(proposed.staffMembers()).as("who it moves, for the checker").isEqualTo(2);
        assertThat(members).extracting(StaffMember::getGrade).containsOnly(DRIVER);

        as("credit2");
        StaffGradeChangeResponse approvedChange = service.decide(proposed.id(), approve("Matches the 2027 grading"));

        assertThat(approvedChange.status()).isEqualTo(StaffGradeChangeStatus.APPROVED);
        assertThat(approvedChange.staffMembers()).isNull();
        assertThat(approvedChange.staffMembersMoved()).isEqualTo(2);
        assertThat(approvedChange.limitOverridesMoved()).isEqualTo(2);
        assertThat(limits).filteredOn(limit -> limit.getGrade().equals(DRIVER))
                .allSatisfy(limit -> {
                    assertThat(limit.getStatus()).isEqualTo(StaffGradeLimitChangeStatus.RETIRED);
                    assertThat(limit.getRetiredBy()).isEqualTo(proposed.id());
                    assertThat(limit.getRetiredAt()).isEqualTo(NOW);
                });
        assertThat(limits).filteredOn(limit -> limit.getGrade().equals("DRIVER/ORDERLY"))
                .extracting(StaffGradeLimitChange::getEffectiveFrom, StaffGradeLimitChange::getMaximumLimit,
                        StaffGradeLimitChange::getStatus, StaffGradeLimitChange::getProposedBy,
                        StaffGradeLimitChange::getDecidedBy)
                .containsExactly(
                        tuple(LocalDate.of(2026, 9, 1), new BigDecimal("50.00"),
                                StaffGradeLimitChangeStatus.APPROVED, "credit1", "credit2"),
                        tuple(LocalDate.of(2026, 11, 1), new BigDecimal("60.00"),
                                StaffGradeLimitChangeStatus.APPROVED, "credit1", "credit2"));
        assertThat(limits).filteredOn(limit -> limit.getGrade().equals("ANALYST"))
                .extracting(StaffGradeLimitChange::getStatus).containsOnly(StaffGradeLimitChangeStatus.APPROVED);

        assertThat(List.of(active, resigned)).extracting(StaffMember::getGrade).containsOnly("DRIVER/ORDERLY");
        assertThat(List.of(active, resigned)).extracting(StaffMember::getUpdatedAt)
                .as("left alone: the offer run reads it as Human Capital acting on a payroll flag")
                .containsOnly(MEMBER_UPDATED);
        assertThat(history).hasSize(2).allSatisfy(change -> {
            assertThat(change.getField()).isEqualTo(StaffFields.GRADE);
            assertThat(change.getPreviousValue()).isEqualTo(DRIVER);
            assertThat(change.getNewValue()).isEqualTo("DRIVER/ORDERLY");
            assertThat(change.getGradeChangeId()).isEqualTo(proposed.id());
            assertThat(change.getBatchId()).isNull();
            assertThat(change.getSubmittedBy()).isEqualTo("credit1");
            assertThat(change.getApprovedBy()).isEqualTo("credit2");
        });
        assertThat(List.of(approved, pending)).extracting(StaffLimitOverride::getGrade).containsOnly("DRIVER/ORDERLY");
        assertThat(approved.appliesTo(active)).as("the override still applies to its member").isTrue();
        assertThat(revoked.getGrade()).as("an ended override keeps the grade it ended at").isEqualTo(DRIVER);
        assertThat(audited()).containsExactly(StaffGradeChangeService.PROPOSED, StaffGradeChangeService.RENAMED);
    }

    @Test
    @DisplayName("a retirement takes the grade's limits out of the matrix; staff who left keep it on their record")
    void retire() {
        StaffMember left = member(3L, "E1003", StaffEmploymentStatus.TERMINATED);
        left.setGrade("ANALYST");

        StaffGradeChangeResponse proposed = service.propose(retire("analyst"));
        as("credit2");
        StaffGradeChangeResponse approved = service.decide(proposed.id(), approve(null));

        assertThat(approved.status()).isEqualTo(StaffGradeChangeStatus.APPROVED);
        assertThat(approved.staffMembersMoved()).isNull();
        assertThat(limits).filteredOn(limit -> limit.getGrade().equals("ANALYST"))
                .extracting(StaffGradeLimitChange::getStatus, StaffGradeLimitChange::getRetiredBy)
                .containsOnly(tuple(StaffGradeLimitChangeStatus.RETIRED, proposed.id()));
        assertThat(left.getGrade()).isEqualTo("ANALYST");
        assertThat(history).isEmpty();
        assertThat(audited()).containsExactly(StaffGradeChangeService.PROPOSED, StaffGradeChangeService.RETIRED);
    }

    @ParameterizedTest
    @EnumSource(value = StaffEmploymentStatus.class, names = {"ACTIVE", "SUSPENDED", "UNPAID_LEAVE"})
    @DisplayName("a grade anyone still employed holds is not retired, neither when proposed nor when approved")
    void retireRefusedWhileEmployedHoldIt(StaffEmploymentStatus status) {
        StaffMember member = member(4L, "E1004", status);

        assertThatThrownBy(() -> service.propose(retire(DRIVER))).isInstanceOf(ConflictException.class)
                .hasMessage("1 staff member still employed holds grade DRIVER/OFFICE ORDERLY; move them to another"
                        + " grade through the staff register first, or rename the grade");

        member.setGrade("OFFICER");
        StaffGradeChangeResponse proposed = service.propose(retire(DRIVER));
        member.setGrade(DRIVER);
        member(5L, "E1005", StaffEmploymentStatus.ACTIVE);
        as("credit2");
        assertThatThrownBy(() -> service.decide(proposed.id(), approve(null))).isInstanceOf(ConflictException.class)
                .hasMessage("2 staff members still employed hold grade DRIVER/OFFICE ORDERLY; move them to another"
                        + " grade through the staff register first, or rename the grade");
        assertThat(limits).extracting(StaffGradeLimitChange::getStatus)
                .containsOnly(StaffGradeLimitChangeStatus.APPROVED);
    }

    @Test
    @DisplayName("what a proposal needs: a grade in the matrix, a new name only to rename, and a different one")
    void proposalRules() {
        assertThatThrownBy(() -> service.propose(rename(DRIVER, null))).isInstanceOf(ValidationException.class)
                .hasMessage("The new name is required to rename a grade");
        assertThatThrownBy(() -> service.propose(rename(DRIVER, "Driver / Office Orderly")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Grade DRIVER/OFFICE ORDERLY is already called that; give it a different name");
        assertThatThrownBy(() -> service.propose(rename(DRIVER, "X".repeat(33))))
                .isInstanceOf(ValidationException.class).hasMessage(StaffGrades.MESSAGE);
        ProposeStaffGradeChangeRequest retireWithName = retire(DRIVER);
        retireWithName.setNewGrade("DRIVER");
        assertThatThrownBy(() -> service.propose(retireWithName)).isInstanceOf(ValidationException.class)
                .hasMessage("A grade being retired takes no new name; propose a RENAME to rename it");
        assertThatThrownBy(() -> service.propose(retire("OFFICER"))).isInstanceOf(NotFoundException.class)
                .hasMessage("Grade OFFICER is not in the grade-to-limit matrix");
        assertThatThrownBy(() -> service.propose(rename(DRIVER, "analyst"))).isInstanceOf(ConflictException.class)
                .hasMessage("Grade ANALYST is already in the grade-to-limit matrix; to merge DRIVER/OFFICE ORDERLY"
                        + " into it, move the staff through the staff register and then retire DRIVER/OFFICE"
                        + " ORDERLY");
        assertThat(changes).isEmpty();
    }

    @Test
    @DisplayName("nothing waiting for approval may land on the grade or the new name")
    void refusedWhileSomethingWaits() {
        StaffGradeLimitChange waitingLimit = keepLimit(StaffGradeLimitChange.builder().grade(DRIVER)
                .scoreBand("Staff").maximumLimit(new BigDecimal("55.00")).effectiveFrom(LocalDate.of(2026, 12, 1))
                .status(StaffGradeLimitChangeStatus.PENDING).proposedBy("credit2").proposedAt(NOW).build());
        assertThatThrownBy(() -> service.propose(retire(DRIVER))).isInstanceOf(ConflictException.class)
                .hasMessage("A limit change to grade DRIVER/OFFICE ORDERLY is waiting for approval (change "
                        + waitingLimit.getId() + "); approve, reject or withdraw it first");
        waitingLimit.setGrade("DRIVER/ORDERLY");
        assertThatThrownBy(() -> service.propose(rename(DRIVER, "Driver/Orderly")))
                .isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("A limit change to grade DRIVER/ORDERLY is waiting for approval");
        limits.remove(waitingLimit);

        pendingBatches.add(15L);
        assertThatThrownBy(() -> service.propose(retire(DRIVER))).isInstanceOf(ConflictException.class)
                .hasMessage("Staff register batch 15, waiting for approval, puts staff at grade DRIVER/OFFICE ORDERLY;"
                        + " approve, reject or withdraw it first");
        pendingBatches.clear();

        StaffGradeChangeResponse renaming = service.propose(rename(DRIVER, "DRIVER/ORDERLY"));
        assertThatThrownBy(() -> service.propose(retire(DRIVER))).isInstanceOf(ConflictException.class)
                .hasMessage("Grade change " + renaming.id() + ", naming grade DRIVER/OFFICE ORDERLY, is waiting for"
                        + " approval; approve, reject or withdraw it first");
        assertThatThrownBy(() -> service.propose(rename("ANALYST", "DRIVER/ORDERLY")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Grade change " + renaming.id() + ", naming grade DRIVER/ORDERLY, is waiting for approval;"
                        + " approve, reject or withdraw it first");

        pendingBatches.add(16L);
        as("credit2");
        assertThatThrownBy(() -> service.decide(renaming.id(), approve(null))).as("checked again at approval")
                .isInstanceOf(ConflictException.class).hasMessageStartingWith("Staff register batch 16");
        assertThat(members).isEmpty();
        assertThat(limits).extracting(StaffGradeLimitChange::getStatus)
                .containsOnly(StaffGradeLimitChangeStatus.APPROVED);
    }

    @Test
    @DisplayName("maker-checker: never decided by its proposer, rejected only with a reason, withdrawn only by them")
    void makerChecker() {
        StaffGradeChangeResponse proposed = service.propose(retire("ANALYST"));

        assertThatThrownBy(() -> service.decide(proposed.id(), approve(null)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("credit1 proposed grade change " + proposed.id() + " and cannot also approve or reject it;"
                        + " another credit manager or SUPER_ADMIN must");
        as("credit2");
        assertThatThrownBy(() -> service.decide(proposed.id(), reject(" "))).isInstanceOf(ValidationException.class)
                .hasMessage("A reason is required to reject a grade change");
        assertThatThrownBy(() -> service.withdraw(proposed.id())).isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only credit1, who proposed grade change " + proposed.id() + ", can withdraw it; anyone"
                        + " else approves or rejects it");

        StaffGradeChangeResponse rejected = service.decide(proposed.id(), reject("ANALYST is still in use"));
        assertThat(rejected.status()).isEqualTo(StaffGradeChangeStatus.REJECTED);
        assertThat(rejected.decisionComment()).isEqualTo("ANALYST is still in use");
        assertThat(limits).extracting(StaffGradeLimitChange::getStatus)
                .containsOnly(StaffGradeLimitChangeStatus.APPROVED);
        assertThatThrownBy(() -> service.decide(proposed.id(), approve(null))).isInstanceOf(ConflictException.class)
                .hasMessage("Grade change " + proposed.id() + " is already rejected");
        assertThatThrownBy(() -> service.decide(999L, approve(null))).isInstanceOf(NotFoundException.class)
                .hasMessage("Grade change 999 not found");

        as("credit1");
        StaffGradeChangeResponse again = service.propose(retire("ANALYST"));
        assertThat(service.withdraw(again.id()).status()).isEqualTo(StaffGradeChangeStatus.WITHDRAWN);
        assertThat(audited()).containsExactly(StaffGradeChangeService.PROPOSED, StaffGradeChangeService.REJECTED,
                StaffGradeChangeService.PROPOSED, StaffGradeChangeService.WITHDRAWN);
    }

    @Test
    @DisplayName("the list is newest first, filtered by a grade as changed or as a new name, and by status")
    void list() {
        StaffGradeChangeResponse retired = service.propose(retire("ANALYST"));
        as("credit2");
        service.decide(retired.id(), approve(null));
        as("credit1");
        StaffGradeChangeResponse renaming = service.propose(rename(DRIVER, "DRIVER/ORDERLY"));

        assertThat(service.changes(null, null)).extracting(StaffGradeChangeResponse::id)
                .containsExactly(renaming.id(), retired.id());
        assertThat(service.changes("driver/orderly", null)).extracting(StaffGradeChangeResponse::id)
                .containsExactly(renaming.id());
        assertThat(service.changes(null, StaffGradeChangeStatus.APPROVED)).extracting(StaffGradeChangeResponse::id)
                .containsExactly(retired.id());
    }

    private static ProposeStaffGradeChangeRequest rename(String grade, String newGrade) {
        return ProposeStaffGradeChangeRequest.builder().action(StaffGradeChangeAction.RENAME).grade(grade)
                .newGrade(newGrade).comment("Human Capital renamed the band").build();
    }

    private static ProposeStaffGradeChangeRequest retire(String grade) {
        return ProposeStaffGradeChangeRequest.builder().action(StaffGradeChangeAction.RETIRE).grade(grade).build();
    }

    private static StaffGradeLimitDecisionRequest approve(String comment) {
        return StaffGradeLimitDecisionRequest.builder().decision(StaffGradeLimitDecision.APPROVED).comment(comment)
                .build();
    }

    private static StaffGradeLimitDecisionRequest reject(String comment) {
        return StaffGradeLimitDecisionRequest.builder().decision(StaffGradeLimitDecision.REJECTED).comment(comment)
                .build();
    }

    private void as(String username) {
        when(authService.getLoggedInUsername()).thenReturn(username);
    }

    private void approvedLimit(String grade, String amount, LocalDate from) {
        keepLimit(StaffGradeLimitChange.builder().grade(grade).scoreBand("Staff").maximumLimit(new BigDecimal(amount))
                .effectiveFrom(from).status(StaffGradeLimitChangeStatus.APPROVED).proposedBy("credit1")
                .proposedAt(NOW.minusDays(40)).decidedBy("credit2").decidedAt(NOW.minusDays(40)).build());
    }

    private StaffMember member(Long id, String employeeNumber, StaffEmploymentStatus status) {
        StaffMember member = StaffMember.builder().id(id).employeeNumber(employeeNumber).fullName("Staff " + id)
                .grade(DRIVER).employmentStatus(status).updatedAt(MEMBER_UPDATED).build();
        members.add(member);
        return member;
    }

    private StaffLimitOverride override(Long id, Long memberId, StaffLimitOverrideStatus status) {
        StaffLimitOverride override = StaffLimitOverride.builder().id(id).staffMemberId(memberId).grade(DRIVER)
                .amount(new BigDecimal("80.00")).status(status).build();
        overrides.add(override);
        return override;
    }

    private StaffGradeChange keepChange(StaffGradeChange change) {
        if (change.getId() == null) {
            change.setId(ids.incrementAndGet());
            changes.add(change);
        }
        return change;
    }

    private StaffGradeLimitChange keepLimit(StaffGradeLimitChange limit) {
        if (limit.getId() == null) {
            limit.setId(ids.incrementAndGet());
            limits.add(limit);
        }
        return limit;
    }

    private List<String> audited() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeastOnce()).record(captor.capture());
        return captor.getAllValues().stream().map(builder -> builder.build().getEventType()).toList();
    }
}
