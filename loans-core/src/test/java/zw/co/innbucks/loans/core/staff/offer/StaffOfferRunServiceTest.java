package zw.co.innbucks.loans.core.staff.offer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffGradeLimit;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitService;
import zw.co.innbucks.loans.core.staff.StaffLoanJobsSwitch;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliation;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliationRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.StaffRegisterVarianceKind;
import zw.co.innbucks.loans.core.staff.StaffRegisterVarianceRepository;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The weekly offer run (FR-SGL-015 to FR-SGL-018, FR-SGL-024): who is offered, refreshed, excluded or withdrawn, the
 * reconciliation gate, and that running again in the same week issues nothing twice. In-memory repositories; the clock
 * stands at Monday 5 October 2026, 08:00 in Harare, and grade C4 lends 300 while C3 lends nothing.
 */
class StaffOfferRunServiceTest {

    /** Monday 5 October 2026, 08:00 in Harare. */
    private static final Instant MONDAY_8AM = Instant.parse("2026-10-05T06:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 6, 0);

    private final List<StaffMember> members = new ArrayList<>();
    private final List<StaffOffer> offers = new ArrayList<>();
    private final List<StaffOfferRun> runs = new ArrayList<>();
    private final List<StaffRegisterReconciliation> reconciliations = new ArrayList<>();
    private final Map<Long, List<String>> flagged = new HashMap<>();
    private final Map<Long, StaffLoanStanding.Standing> standings = new HashMap<>();
    private Clock clock = Clock.fixed(MONDAY_8AM, ZoneOffset.UTC);
    private final List<StaffLimitOverride> overrides = new ArrayList<>();
    private boolean lockFree = true;
    private StaffMemberRepository memberRepository;
    private StaffOfferRepository offerRepository;
    private AuditService auditService;
    private final StaffLoanJobsSwitch jobsSwitch = mock(StaffLoanJobsSwitch.class);
    /** Each call's offers, as the run handed them over to be notified. */
    private final List<List<StaffOffer>> notified = new ArrayList<>();
    private StaffOfferRunService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(StaffMemberRepository.class);
        when(memberRepository.findAll(any(Sort.class))).thenAnswer(i -> members.stream()
                .sorted(Comparator.comparing(StaffMember::getEmployeeNumber)).toList());

        offerRepository = mock(StaffOfferRepository.class);
        when(offerRepository.findByStatus(any())).thenAnswer(i -> offers.stream()
                .filter(o -> o.getStatus() == i.getArgument(0)).toList());
        when(offerRepository.findMemberIdsByCycleStart(any())).thenAnswer(i -> offers.stream()
                .filter(o -> o.getCycleStart().equals(i.getArgument(0))).map(StaffOffer::getStaffMemberId)
                .collect(Collectors.toSet()));
        when(offerRepository.expireDue(any(), any(), any())).thenAnswer(i -> {
            LocalDateTime now = i.getArgument(0);
            int expired = 0;
            for (StaffOffer offer : offers) {
                if (offer.getStatus() == StaffOfferStatus.ACTIVE && !offer.getExpiresAt().isAfter(now)) {
                    offer.close(StaffOfferStatus.EXPIRED, offer.getExpiresAt(), null);
                    expired++;
                }
            }
            return expired;
        });
        when(offerRepository.saveAll(any())).thenAnswer(i -> {
            Iterable<StaffOffer> saved = i.getArgument(0);
            saved.forEach(offer -> {
                offer.setId((long) offers.size() + 1);
                offers.add(offer);
            });
            assertThat(offers.stream().filter(o -> o.getStatus() == StaffOfferStatus.ACTIVE)
                    .collect(Collectors.groupingBy(StaffOffer::getStaffMemberId, Collectors.counting())))
                    .as("at most one ACTIVE offer per member, as the database insists")
                    .allSatisfy((member, count) -> assertThat(count).isEqualTo(1L));
            return saved;
        });

        StaffOfferRunRepository runRepository = mock(StaffOfferRunRepository.class);
        when(runRepository.tryLock(anyLong())).thenAnswer(i -> lockFree);
        when(runRepository.save(any())).thenAnswer(i -> {
            StaffOfferRun run = i.getArgument(0);
            if (run.getId() == null) {
                run.setId((long) runs.size() + 1);
                runs.add(run);
            }
            return run;
        });
        when(runRepository.findById(anyLong())).thenAnswer(i -> runs.stream()
                .filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(runRepository.findFirstByOrderByIdDesc()).thenAnswer(i -> runs.isEmpty() ? Optional.empty()
                : Optional.of(runs.getLast()));
        when(runRepository.findAll(any(Pageable.class))).thenAnswer(i -> new PageImpl<>(runs.reversed(),
                (Pageable) i.getArgument(0), runs.size()));

        StaffRegisterReconciliationRepository reconciliationRepository =
                mock(StaffRegisterReconciliationRepository.class);
        when(reconciliationRepository.findFirstByOrderByIdDesc()).thenAnswer(i -> reconciliations.isEmpty()
                ? Optional.empty() : Optional.of(reconciliations.getLast()));
        StaffRegisterVarianceRepository varianceRepository = mock(StaffRegisterVarianceRepository.class);
        when(varianceRepository.findEmployeeNumbers(anyLong(), any())).thenAnswer(i -> {
            Collection<StaffRegisterVarianceKind> kinds = i.getArgument(1);
            assertThat(kinds).containsExactlyInAnyOrder(StaffRegisterVarianceKind.LEFT_ON_PAYROLL,
                    StaffRegisterVarianceKind.NOT_ON_PAYROLL);
            return flagged.getOrDefault((Long) i.getArgument(0), List.of());
        });

        StaffGradeLimitService gradeLimitService = mock(StaffGradeLimitService.class);
        when(gradeLimitService.limitsOn(any())).thenReturn(Map.of(
                "C4", new StaffGradeLimit(1L, "C4", "Band C", new BigDecimal("300.00"), LocalDate.of(2026, 10, 1),
                        "credit2", LocalDateTime.of(2026, 9, 30, 8, 5)),
                "C3", new StaffGradeLimit(4L, "C3", "Band D", BigDecimal.ZERO.setScale(2), LocalDate.of(2026, 10, 1),
                        "credit2", LocalDateTime.of(2026, 9, 30, 8, 5))));
        when(gradeLimitService.limitOn(any(), any())).thenAnswer(i -> Optional.ofNullable(
                gradeLimitService.limitsOn(i.getArgument(1)).get((String) i.getArgument(0))));
        when(memberRepository.findById(anyLong())).thenAnswer(i -> members.stream()
                .filter(m -> m.getId().equals(i.getArgument(0))).findFirst());
        when(offerRepository.findByStaffMemberIdAndStatus(anyLong(), any())).thenAnswer(i -> offers.stream()
                .filter(o -> o.getStaffMemberId().equals(i.getArgument(0)) && o.getStatus() == i.getArgument(1))
                .findFirst());
        when(offerRepository.save(any())).thenAnswer(i -> {
            StaffOffer offer = i.getArgument(0);
            offer.setId((long) offers.size() + 1);
            offers.add(offer);
            return offer;
        });
        auditService = mock(AuditService.class);
        StaffLimitOverrideRepository overrideRepository = mock(StaffLimitOverrideRepository.class);
        when(overrideRepository.findByStaffMemberIdAndStatus(anyLong(), any())).thenAnswer(i -> overrides.stream()
                .filter(o -> o.getStaffMemberId().equals(i.getArgument(0)) && o.getStatus() == i.getArgument(1))
                .findFirst());
        when(overrideRepository.findByStatus(any())).thenAnswer(i -> overrides.stream()
                .filter(o -> o.getStatus() == i.getArgument(0)).toList());
        StaffOfferProperties properties = new StaffOfferProperties();
        StaffNotificationService notificationService = mock(StaffNotificationService.class);
        doAnswer(i -> {
            Collection<StaffOffer> issued = i.getArgument(0);
            Map<Long, StaffMember> byId = i.getArgument(1);
            assertThat(issued).allSatisfy(offer -> {
                assertThat(offer.getId()).as("notified after it is saved").isNotNull();
                assertThat(byId).containsKey(offer.getStaffMemberId());
            });
            notified.add(List.copyOf(issued));
            return null;
        }).when(notificationService).notifyOffers(any(), any());
        service = new StaffOfferRunService(offerRepository, runRepository, memberRepository, reconciliationRepository,
                varianceRepository, gradeLimitService, members -> standings, overrideRepository, properties,
                notificationService, auditService, new MarketTimeZone("ZW", clockProxy()), jobsSwitch);
    }

    /** The clock the service reads, which a test can move. */
    private Clock clockProxy() {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return clock.getZone();
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return clock.withZone(zone);
            }

            @Override
            public Instant instant() {
                return clock.instant();
            }
        };
    }

    private StaffMember member(String employeeNumber, String grade, StaffEmploymentStatus status) {
        StaffMember member = StaffMember.builder().id((long) members.size() + 1).employeeNumber(employeeNumber)
                .fullName("Staff " + employeeNumber).nationalId("632345678B4" + members.size())
                .msisdn("26377123400" + members.size()).grade(grade).department("Finance").employmentStatus(status)
                .engagementDate(LocalDate.of(2015, 2, 2)).walletAccountNumber("26377123400" + members.size())
                .statusChangedAt(LocalDateTime.of(2026, 9, 1, 8, 0)).createdBatchId(1L)
                .createdAt(LocalDateTime.of(2026, 9, 1, 8, 0)).updatedBatchId(1L)
                .updatedAt(LocalDateTime.of(2026, 9, 1, 8, 0)).build();
        members.add(member);
        return member;
    }

    private StaffRegisterReconciliation reconciled(LocalDateTime runAt, String... flaggedEmployees) {
        StaffRegisterReconciliation reconciliation = StaffRegisterReconciliation.builder()
                .id((long) reconciliations.size() + 1).fileName("payroll.csv").fileSha256("ab").runBy("hc1")
                .runAt(runAt).comparedFields("").payrollRows(1).registerMembers(1).matched(0).different(0)
                .leftOnPayroll(0).leftOnPayrollEligible(0).notOnRegister(0).notOnPayroll(0).notOnPayrollEligible(0)
                .duplicatesOnPayroll(0).unreadableRows(0).build();
        reconciliations.add(reconciliation);
        flagged.put(reconciliation.getId(), List.of(flaggedEmployees));
        return reconciliation;
    }

    private void at(String instant) {
        clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private List<StaffOffer> activeOffers() {
        return offers.stream().filter(o -> o.getStatus() == StaffOfferStatus.ACTIVE).toList();
    }

    private List<String> audited() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(builder -> builder.build().getEventType()).toList();
    }

    @Test
    @DisplayName("every eligible member is offered their grade's limit for a week; the ineligible are counted, not offered")
    void offersTheEligible() {
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        StaffMember active = member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        member("E1012", "C3", StaffEmploymentStatus.ACTIVE);
        member("E1043", "C4", StaffEmploymentStatus.RESIGNED);
        member("E1044", "B9", StaffEmploymentStatus.ACTIVE);

        StaffOfferRunResponse run = service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");

        assertThat(run.status()).isEqualTo(StaffOfferRunStatus.COMPLETED);
        assertThat(run.cycleStart()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(run.reconciliationId()).isEqualTo(1L);
        assertThat(run.registerMembers()).isEqualTo(4);
        assertThat(run.ineligible()).as("C3 lends nothing, E1043 resigned, B9 has no limit").isEqualTo(3);
        assertThat(run.eligible()).isEqualTo(1);
        assertThat(run.offered()).isEqualTo(1);
        assertThat(run.refreshed()).isZero();
        assertThat(run.alreadyOffered()).isZero();
        assertThat(run.startedAt()).isEqualTo(NOW);
        assertThat(offers).singleElement().satisfies(offer -> {
            assertThat(offer.getStaffMemberId()).isEqualTo(active.getId());
            assertThat(offer.getRunId()).isEqualTo(run.id());
            assertThat(offer.getAmount()).isEqualByComparingTo("300.00");
            assertThat(offer.getGrade()).isEqualTo("C4");
            assertThat(offer.getScoreBand()).isEqualTo("Band C");
            assertThat(offer.getGradeLimitChangeId()).isEqualTo(1L);
            assertThat(offer.getIssuedAt()).isEqualTo(NOW);
            assertThat(offer.getExpiresAt()).as("seven days by default").isEqualTo(NOW.plusDays(7));
            assertThat(offer.getReplacesOfferId()).isNull();
        });
        verify(memberRepository).lockRegister(StaffRegisterService.REGISTER_LOCK);
        assertThat(audited()).containsExactly(StaffOfferRunService.COMPLETED);
    }

    @Test
    @DisplayName("running again in the same week issues nothing twice, but does offer anyone added since")
    void sameWeekTopsUp() {
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");
        member("E1050", "C4", StaffEmploymentStatus.ACTIVE);
        at("2026-10-07T12:00:00Z");

        StaffOfferRunResponse again = service.run(StaffOfferRunTrigger.MANUAL, "credit1");

        assertThat(again.cycleStart()).as("Wednesday is still the week of Monday the 5th")
                .isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(again.alreadyOffered()).isEqualTo(1);
        assertThat(again.offered()).isEqualTo(1);
        assertThat(again.trigger()).isEqualTo(StaffOfferRunTrigger.MANUAL);
        assertThat(again.startedBy()).isEqualTo("credit1");
        assertThat(offers).extracting(StaffOffer::getStaffMemberId).containsExactly(1L, 2L);
        assertThat(service.run(StaffOfferRunTrigger.MANUAL, "credit1").eligible())
                .as("a third run in the week finds nothing to do").isEqualTo(2);
        assertThat(offers).hasSize(2);
        assertThat(notified).as("each run notifies only the offers it issued, so nobody is told twice (FR-SGL-024)")
                .extracting(batch -> batch.stream().map(StaffOffer::getStaffMemberId).toList())
                .containsExactly(List.of(1L), List.of(2L), List.of());
    }

    @Test
    @DisplayName("next week an open offer is refreshed, and a lapsed one is closed at its expiry and replaced")
    void nextWeekRefreshesAndExpires() {
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        member("E1012", "C4", StaffEmploymentStatus.ACTIVE);
        service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");
        // E1012's offer was issued with a shorter life, so it has lapsed by next week's run.
        offers.get(1).setExpiresAt(NOW.plusDays(3));
        // A second before the hour: last week's offers, issued a moment after it, are still open.
        at("2026-10-12T05:59:59Z");

        StaffOfferRunResponse next = service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");

        assertThat(next.cycleStart()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(next.expired()).isEqualTo(1);
        assertThat(next.refreshed()).as("E1001's offer was still open").isEqualTo(1);
        assertThat(next.offered()).as("E1012 held nothing open").isEqualTo(1);
        assertThat(offers).extracting(StaffOffer::getStatus).containsExactly(StaffOfferStatus.SUPERSEDED,
                StaffOfferStatus.EXPIRED, StaffOfferStatus.ACTIVE, StaffOfferStatus.ACTIVE);
        assertThat(offers.get(0).getClosedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 5, 59, 59));
        assertThat(offers.get(1).getClosedAt()).as("closed at the moment it lapsed").isEqualTo(NOW.plusDays(3));
        assertThat(offers.get(2).getReplacesOfferId()).isEqualTo(1L);
        assertThat(offers.get(3).getReplacesOfferId()).isNull();
        assertThat(notified.getLast()).as("the refreshed offer and the new one are both notified (FR-SGL-019)")
                .containsExactly(offers.get(2), offers.get(3));
    }

    @Test
    @DisplayName("an active loan, arrears or a reconciliation that lists them as gone excludes a member and withdraws"
            + " their offer")
    void exclusions() {
        StaffRegisterReconciliation first = reconciled(LocalDateTime.of(2026, 9, 28, 9, 0));
        StaffMember withLoan = member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        StaffMember inArrears = member("E1002", "C4", StaffEmploymentStatus.ACTIVE);
        member("E1003", "C4", StaffEmploymentStatus.ACTIVE);
        StaffMember corrected = member("E1004", "C4", StaffEmploymentStatus.ACTIVE);
        member("E1005", "C4", StaffEmploymentStatus.ACTIVE);
        at("2026-09-28T12:00:00Z");
        service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");
        assertThat(activeOffers()).hasSize(5);

        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40), "E1003", "E1004");
        corrected.setUpdatedAt(LocalDateTime.of(2026, 10, 2, 14, 0));
        standings.put(withLoan.getId(), StaffLoanStanding.Standing.ACTIVE_LOAN);
        standings.put(inArrears.getId(), StaffLoanStanding.Standing.ARREARS);
        at("2026-10-05T06:00:00Z");

        StaffOfferRunResponse run = service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");

        assertThat(run.reconciliationId()).as("the latest reconciliation").isNotEqualTo(first.getId());
        assertThat(run.excludedActiveLoan()).isEqualTo(1);
        assertThat(run.excludedArrears()).isEqualTo(1);
        assertThat(run.excludedByReconciliation()).as("E1003; E1004's record was corrected since").isEqualTo(1);
        assertThat(run.eligible()).isEqualTo(2);
        assertThat(run.refreshed()).isEqualTo(2);
        assertThat(run.withdrawn()).isEqualTo(3);
        assertThat(offers.subList(0, 3)).extracting(StaffOffer::getStatus, StaffOffer::getClosedReason)
                .containsExactly(
                        tuple(StaffOfferStatus.WITHDRAWN, "Holds an active Staff Grocery Loan"),
                        tuple(StaffOfferStatus.WITHDRAWN, "In arrears on a Staff Grocery Loan"),
                        tuple(StaffOfferStatus.WITHDRAWN, "Payroll reconciliation 2 lists them as having left or"
                                + " not on the payroll"));
        assertThat(activeOffers()).extracting(StaffOffer::getStaffMemberId).containsExactly(4L, 5L);
    }

    private StaffLimitOverride override(StaffMember member, String grade, String amount) {
        StaffLimitOverride override = StaffLimitOverride.builder().id((long) overrides.size() + 1)
                .staffMemberId(member.getId()).grade(grade).amount(new BigDecimal(amount)).reason("Credit decision")
                .status(StaffLimitOverrideStatus.APPROVED).proposedBy("credit1").proposedAt(NOW).decidedBy("credit2")
                .decidedAt(NOW).build();
        overrides.add(override);
        return override;
    }

    @Test
    @DisplayName("Credit's override sets the amount; 0 excludes the member; one set for another grade does not apply")
    void overrides() {
        StaffMember lowered = member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        StaffMember blocked = member("E1002", "C4", StaffEmploymentStatus.ACTIVE);
        StaffMember regraded = member("E1003", "C4", StaffEmploymentStatus.ACTIVE);
        StaffMember ineligible = member("E1004", "C3", StaffEmploymentStatus.ACTIVE);
        reconciled(LocalDateTime.of(2026, 9, 28, 9, 0));
        at("2026-09-28T12:00:00Z");
        service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");
        StaffLimitOverride lower = override(lowered, "C4", "150.00");
        StaffLimitOverride block = override(blocked, "C4", "0.00");
        override(regraded, "C3", "500.00");
        override(ineligible, "C3", "500.00");
        override(member("E1005", "C4", StaffEmploymentStatus.ACTIVE), "C4", "200.00")
                .setStatus(StaffLimitOverrideStatus.REVOKED);
        at("2026-10-05T06:00:00Z");

        StaffOfferRunResponse run = service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");

        assertThat(run.excludedByOverride()).isEqualTo(1);
        assertThat(run.ineligible()).as("an override cannot make a grade without a limit eligible").isEqualTo(1);
        assertThat(run.eligible()).isEqualTo(3);
        assertThat(run.registerMembers()).isEqualTo(5);
        assertThat(offers.get(1).getStatus()).isEqualTo(StaffOfferStatus.WITHDRAWN);
        assertThat(offers.get(1).getClosedReason()).isEqualTo("Credit set their limit to 0 (limit override "
                + block.getId() + ")");
        assertThat(activeOffers()).extracting(StaffOffer::getStaffMemberId, StaffOffer::getAmount,
                        StaffOffer::getLimitOverrideId)
                .containsExactly(
                        tuple(lowered.getId(), new BigDecimal("150.00"), lower.getId()),
                        tuple(regraded.getId(), new BigDecimal("300.00"), null),
                        tuple(5L, new BigDecimal("300.00"), null));
    }

    @Test
    @DisplayName("a member who stops qualifying loses their open offer with the reason")
    void withdrawsTheIneligible() {
        reconciled(LocalDateTime.of(2026, 9, 29, 9, 40));
        StaffMember suspended = member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        StaffMember regraded = member("E1002", "C4", StaffEmploymentStatus.ACTIVE);
        at("2026-09-30T06:00:00Z");
        service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");
        suspended.setEmploymentStatus(StaffEmploymentStatus.SUSPENDED);
        regraded.setGrade("C3");
        at("2026-10-05T06:00:00Z");

        StaffOfferRunResponse run = service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");

        assertThat(run.ineligible()).isEqualTo(2);
        assertThat(run.withdrawn()).isEqualTo(2);
        assertThat(offers).extracting(StaffOffer::getStatus, StaffOffer::getClosedReason).containsExactly(
                tuple(StaffOfferStatus.WITHDRAWN, "No longer eligible: Employment status is SUSPENDED; only ACTIVE"
                        + " staff may borrow"),
                tuple(StaffOfferStatus.WITHDRAWN, "No longer eligible: Grade C3's limit is 0, so it is not lent to"));
    }

    @Test
    @DisplayName("no reconciliation, or one older than the limit, refuses the run: recorded, audited, nothing offered")
    void gate() {
        member("E1001", "C4", StaffEmploymentStatus.ACTIVE);

        StaffOfferRunResponse never = service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");

        assertThat(never.status()).isEqualTo(StaffOfferRunStatus.REFUSED);
        assertThat(never.reason()).isEqualTo(StaffOfferRunService.NEVER_RECONCILED);
        assertThat(never.reconciliationId()).isNull();
        assertThat(never.registerMembers()).as("a refused run has no counts").isNull();

        reconciled(LocalDateTime.of(2026, 8, 30, 9, 0));
        StaffOfferRunResponse stale = service.run(StaffOfferRunTrigger.MANUAL, "credit1");

        assertThat(stale.status()).isEqualTo(StaffOfferRunStatus.REFUSED);
        assertThat(stale.reason()).isEqualTo("The staff register was last reconciled against the payroll master on"
                + " 2026-08-30, 36 days ago; offers need a reconciliation within the last 35 days");
        assertThat(stale.reconciliationId()).isEqualTo(1L);
        assertThat(offers).isEmpty();
        assertThat(notified).as("a refused run tells nobody anything").isEmpty();
        verify(offerRepository, never()).expireDue(any(), any(), any());
        assertThat(audited()).containsExactly(StaffOfferRunService.REFUSED, StaffOfferRunService.REFUSED);
    }

    @Test
    @DisplayName("the age is counted in market days: 35 days old still runs, 36 does not")
    void gateBoundary() {
        reconciled(LocalDateTime.of(2026, 8, 31, 21, 59));
        member("E1001", "C4", StaffEmploymentStatus.ACTIVE);

        assertThat(service.schedule().reconciliationAgeDays()).as("23:59 on 31 August in Harare").isEqualTo(35);
        assertThat(service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler").status())
                .isEqualTo(StaffOfferRunStatus.COMPLETED);

        reconciliations.clear();
        reconciled(LocalDateTime.of(2026, 8, 30, 22, 0));
        assertThat(service.schedule().reconciliationAgeDays()).as("00:00 on 31 August in Harare: still 35")
                .isEqualTo(35);
        reconciliations.clear();
        reconciled(LocalDateTime.of(2026, 8, 30, 21, 59));
        assertThat(service.schedule().readyToRun()).as("23:59 on 30 August: 36 days").isFalse();
    }

    @Test
    @DisplayName("a second run while one is in progress is refused outright and records nothing")
    void oneAtATime() {
        lockFree = false;

        assertThatThrownBy(() -> service.run(StaffOfferRunTrigger.MANUAL, "credit1"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("An offer run is already in progress; try again once it has finished");
        assertThat(runs).isEmpty();
        verify(memberRepository, never()).lockRegister(anyLong());
    }

    @Test
    @DisplayName("a failure is recorded on its own, with the cycle it was for")
    void recordsFailures() {
        StaffOfferRunResponse failed = service.recordFailure(StaffOfferRunTrigger.SCHEDULED, "scheduler", NOW,
                new IllegalStateException("database went away"));

        assertThat(failed.status()).isEqualTo(StaffOfferRunStatus.FAILED);
        assertThat(failed.cycleStart()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(failed.reason()).isEqualTo("The run failed and nothing it did was kept: IllegalStateException:"
                + " database went away");
        assertThat(failed.registerMembers()).isNull();
        assertThat(audited()).containsExactly(StaffOfferRunService.FAILED);
    }

    @Test
    @DisplayName("the schedule says when the run next fires on the market's clock, and whether it would go ahead")
    void schedule() {
        StaffOfferScheduleResponse before = service.schedule();
        assertThat(before.runCron()).isEqualTo("0 0 8 * * MON");
        assertThat(before.zone()).isEqualTo("Africa/Harare");
        assertThat(before.nextScheduledRunAt()).as("08:00 Harare next Monday, stored as UTC")
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 6, 0));
        assertThat(before.automaticRuns()).as("the jobs are off here: runs are started from the portal").isFalse();
        assertThat(before.readyToRun()).isFalse();
        assertThat(before.notReadyReason()).isEqualTo(StaffOfferRunService.NEVER_RECONCILED);
        assertThat(before.lastReconciliationId()).isNull();
        assertThat(before.lastRun()).isNull();

        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");
        StaffOfferScheduleResponse after = service.schedule();

        assertThat(after.readyToRun()).isTrue();
        assertThat(after.notReadyReason()).isNull();
        assertThat(after.lastReconciliationId()).isEqualTo(1L);
        assertThat(after.reconciliationAgeDays()).isEqualTo(3);
        assertThat(after.validityDays()).isEqualTo(7);
        assertThat(after.maxReconciliationAgeDays()).isEqualTo(35);
        assertThat(after.lastRun().id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("where the Staff Grocery Loan jobs run, the schedule says the weekly run fires by itself")
    void scheduleWithTheJobsOn() {
        when(jobsSwitch.on()).thenReturn(true);

        assertThat(service.schedule().automaticRuns()).isTrue();
    }

    @Test
    @DisplayName("runs read newest first; an unknown run is a 404")
    void reads() {
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");
        service.run(StaffOfferRunTrigger.MANUAL, "credit1");

        assertThat(service.runs(PageRequest.of(0, 20)).getContent()).extracting(StaffOfferRunResponse::id)
                .containsExactly(2L, 1L);
        assertThat(service.run(1L).trigger()).isEqualTo(StaffOfferRunTrigger.SCHEDULED);
        assertThatThrownBy(() -> service.run(99L)).isInstanceOf(NotFoundException.class)
                .hasMessage("Staff offer run 99 not found");
    }

    @Test
    @DisplayName("a week runs Monday to Sunday on the market's calendar")
    void cycles() {
        assertThat(StaffOfferRunService.cycleOf(LocalDate.of(2026, 10, 5))).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(StaffOfferRunService.cycleOf(LocalDate.of(2026, 10, 11))).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(StaffOfferRunService.cycleOf(LocalDate.of(2026, 10, 12))).isEqualTo(LocalDate.of(2026, 10, 12));
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        at("2026-10-11T22:30:00Z");
        assertThat(service.run(StaffOfferRunTrigger.MANUAL, "credit1").cycleStart())
                .as("00:30 on Monday the 12th in Harare is the new week, though it is still Sunday in UTC")
                .isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(runs.getFirst().getStartedAt()).isEqualTo(LocalDateTime.of(2026, 10, 11, 22, 30));
    }

    // --- A borrower applying in the SuperApp (FR-SGL-025) ---

    @Test
    @DisplayName("applying without an offer makes one on demand, on the run's terms, in no run and not notified")
    void applyMakesAnOffer() {
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        StaffMember member = member("E1001", "C4", StaffEmploymentStatus.ACTIVE);

        StaffOfferApplication application = service.apply(member.getId());

        assertThat(application.created()).isTrue();
        assertThat(application.verdict()).isEqualTo(StaffOfferVerdict.ELIGIBLE);
        assertThat(application.offer()).satisfies(offer -> {
            assertThat(offer.getOrigin()).isEqualTo(StaffOfferOrigin.APPLY);
            assertThat(offer.getRunId()).isNull();
            assertThat(offer.getCycleStart()).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(offer.getAmount()).isEqualByComparingTo("300.00");
            assertThat(offer.getScoreBand()).isEqualTo("Band C");
            assertThat(offer.getGradeLimitChangeId()).isEqualTo(1L);
            assertThat(offer.getIssuedAt()).isEqualTo(NOW);
            assertThat(offer.getExpiresAt()).isEqualTo(NOW.plusDays(7));
            assertThat(offer.getStatus()).isEqualTo(StaffOfferStatus.ACTIVE);
        });
        assertThat(notified).isEmpty();
        assertThat(runs).isEmpty();
        verify(memberRepository).lockRegister(StaffRegisterService.REGISTER_LOCK);
        assertThat(audited()).containsExactly(StaffOfferRunService.APPLIED);
    }

    @Test
    @DisplayName("applying while holding an open offer returns it and makes nothing")
    void applyReturnsTheHeldOffer() {
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        StaffMember member = member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");
        StaffOffer held = offers.getFirst();

        StaffOfferApplication application = service.apply(member.getId());

        assertThat(application.created()).isFalse();
        assertThat(application.offer()).isSameAs(held);
        assertThat(offers).hasSize(1);
    }

    @Test
    @DisplayName("an offer past its expiry that no run closed is closed EXPIRED at its expiry, and a new one made")
    void applyReplacesALapsedOffer() {
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        StaffMember member = member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        service.run(StaffOfferRunTrigger.SCHEDULED, "scheduler");
        StaffOffer lapsed = offers.getFirst();
        at("2026-10-12T07:00:00Z");

        StaffOfferApplication application = service.apply(member.getId());

        assertThat(lapsed.getStatus()).isEqualTo(StaffOfferStatus.EXPIRED);
        assertThat(lapsed.getClosedAt()).isEqualTo(lapsed.getExpiresAt());
        assertThat(application.created()).isTrue();
        assertThat(application.offer().getCycleStart()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    @DisplayName("a member the run would not offer is declined with the run's own reason, and nothing is made")
    void applyDeclinesWhomTheRunWouldNotOffer() {
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40), "E1005");
        StaffMember resigned = member("E1001", "C4", StaffEmploymentStatus.RESIGNED);
        StaffMember noLimit = member("E1002", "C3", StaffEmploymentStatus.ACTIVE);
        StaffMember arrears = member("E1003", "C4", StaffEmploymentStatus.ACTIVE);
        StaffMember holding = member("E1004", "C4", StaffEmploymentStatus.ACTIVE);
        StaffMember flaggedMember = member("E1005", "C4", StaffEmploymentStatus.ACTIVE);
        StaffMember blocked = member("E1006", "C4", StaffEmploymentStatus.ACTIVE);
        standings.put(arrears.getId(), StaffLoanStanding.Standing.ARREARS);
        standings.put(holding.getId(), StaffLoanStanding.Standing.ACTIVE_LOAN);
        override(blocked, "C4", "0.00");

        assertThat(List.of(resigned, noLimit, arrears, holding, flaggedMember, blocked))
                .extracting(member -> service.apply(member.getId()).verdict())
                .containsExactly(StaffOfferVerdict.NOT_ACTIVE, StaffOfferVerdict.NO_LIMIT, StaffOfferVerdict.ARREARS,
                        StaffOfferVerdict.ACTIVE_LOAN, StaffOfferVerdict.PAYROLL_FLAGGED, StaffOfferVerdict.LIMIT_ZERO);
        assertThat(offers).isEmpty();
        assertThat(audited()).isEmpty();
    }

    @Test
    @DisplayName("Credit's override sets the amount of an offer made on demand, as it does the run's")
    void applyHonoursTheOverride() {
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        StaffMember member = member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        StaffLimitOverride lower = override(member, "C4", "150.00");

        StaffOffer offer = service.apply(member.getId()).offer();

        assertThat(offer.getAmount()).isEqualByComparingTo("150.00");
        assertThat(offer.getLimitOverrideId()).isEqualTo(lower.getId());
    }

    @Test
    @DisplayName("with the register not reconciled recently enough nothing is made, though an offer held stands")
    void applyRespectsTheGate() {
        StaffMember member = member("E1001", "C4", StaffEmploymentStatus.ACTIVE);

        StaffOfferApplication refused = service.apply(member.getId());

        assertThat(refused.offer()).isNull();
        assertThat(refused.unavailable()).isEqualTo(StaffOfferRunService.NEVER_RECONCILED);
        assertThat(offers).isEmpty();
        assertThat(service.assess(member).unavailable()).isEqualTo(StaffOfferRunService.NEVER_RECONCILED);
        assertThat(service.assess(member).verdict()).isEqualTo(StaffOfferVerdict.ELIGIBLE);
    }

    @Test
    @DisplayName("a member who applied this week is not offered again by a run in the same week")
    void aRunDoesNotOfferAgainAfterAnApplication() {
        reconciled(LocalDateTime.of(2026, 10, 2, 9, 40));
        StaffMember member = member("E1001", "C4", StaffEmploymentStatus.ACTIVE);
        service.apply(member.getId());

        StaffOfferRunResponse run = service.run(StaffOfferRunTrigger.MANUAL, "credit1");

        assertThat(run.alreadyOffered()).isEqualTo(1);
        assertThat(run.offered()).isZero();
        assertThat(offers).hasSize(1);
        assertThat(notified).singleElement().satisfies(batch -> assertThat(batch).isEmpty());
    }
}
