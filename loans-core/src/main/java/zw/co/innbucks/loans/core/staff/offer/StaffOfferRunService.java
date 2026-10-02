package zw.co.innbucks.loans.core.staff.offer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.staff.StaffGradeLimit;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitService;
import zw.co.innbucks.loans.core.staff.StaffLoanJobsSwitch;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffMemberResponse;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliation;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliationRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.StaffRegisterVarianceKind;
import zw.co.innbucks.loans.core.staff.StaffRegisterVarianceRepository;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The weekly Offer Generation Service for the Staff Grocery Loan (FR-SGL-015 to FR-SGL-018, FR-SGL-024).
 *
 * <p><b>The gate.</b> The staff register is the product's only credit control, so a run goes ahead only when the
 * register has been reconciled against the HR payroll master within {@code max-reconciliation-age-days}; otherwise it
 * is REFUSED and recorded, and nobody is offered anything. Members the latest reconciliation lists as having left
 * (LEFT_ON_PAYROLL) or as missing from the payroll (NOT_ON_PAYROLL) are excluded until Human Capital corrects their
 * record or a later reconciliation no longer lists them.</p>
 *
 * <p><b>The run.</b> Offers past their expiry are closed. Then every member of the register is either ineligible (not
 * ACTIVE, or their grade has no limit above zero today), excluded (an active loan or arrears under this product, a
 * written-off balance Credit has not overridden (FR-SGL-014), flagged by the reconciliation, or a limit of 0 set by
 * Credit), or eligible. An eligible member without this cycle's offer gets one at their grade's limit, or at the
 * limit Credit's override sets for them (FR-SGL-011), valid for {@code validity-days} and replacing any offer they
 * still hold; an ineligible or excluded member's open offer is withdrawn with the reason.</p>
 *
 * <p><b>Re-running.</b> A run belongs to a cycle, the market week it falls in, and a member gets at most one offer per
 * cycle (the database enforces it). A whole run is one transaction under two locks: one that refuses a second run
 * while one is in progress, and the register's lock, so no approval changes the register mid-run. So a run that fails
 * leaves nothing behind, and running again in the same week only tops up the members still without an offer, such as
 * those added to the register since.</p>
 *
 * <p><b>Telling them.</b> Every member issued an offer, new or refreshed, gets a notification created in the same
 * transaction (FR-SGL-019), one per offer, so a retried or topped-up run never tells anyone twice; it is sent to their
 * phone once the run commits.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffOfferRunService {

    static final String COMPLETED = "STAFF_OFFER_RUN_COMPLETED";
    static final String REFUSED = "STAFF_OFFER_RUN_REFUSED";
    static final String FAILED = "STAFF_OFFER_RUN_FAILED";
    static final String APPLIED = "STAFF_OFFER_APPLIED";
    /** The advisory lock that keeps two runs from overlapping: "STAFFOFR". */
    static final long RUN_LOCK = 0x53544146464F4652L;
    static final String NEVER_RECONCILED = "The staff register has never been reconciled against the payroll master;"
            + " Human Capital must run a reconciliation before offers can be generated";
    private static final String ENTITY = "STAFF_OFFER_RUN";
    private static final int MAX_REASON = 500;

    private final StaffOfferRepository offerRepository;
    private final StaffOfferRunRepository runRepository;
    private final StaffMemberRepository memberRepository;
    private final StaffRegisterReconciliationRepository reconciliationRepository;
    private final StaffRegisterVarianceRepository varianceRepository;
    private final StaffGradeLimitService gradeLimitService;
    private final StaffLoanStanding loanStanding;
    private final StaffLimitOverrideRepository overrideRepository;
    private final StaffArrearsOverrideRepository arrearsOverrideRepository;
    private final StaffOfferProperties properties;
    private final StaffNotificationService notificationService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;
    private final StaffLoanJobsSwitch jobsSwitch;

    /**
     * Runs the current cycle: offers every eligible member still without this cycle's offer.
     *
     * @return the attempt, COMPLETED, or REFUSED when the register is not reconciled recently enough
     * @throws ConflictException another run is in progress
     */
    @Transactional
    public StaffOfferRunResponse run(StaffOfferRunTrigger trigger, String startedBy) {
        if (!runRepository.tryLock(RUN_LOCK)) {
            throw new ConflictException("An offer run is already in progress; try again once it has finished");
        }
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        LocalDateTime now = marketTimeZone.nowUtc();
        LocalDate today = marketTimeZone.today();
        StaffOfferRun.StaffOfferRunBuilder attempt = StaffOfferRun.builder()
                .cycleStart(cycleOf(today))
                .trigger(trigger)
                .startedBy(startedBy)
                .startedAt(now)
                .finishedAt(now);

        Gate gate = gate(today);
        if (gate.refusal() != null) {
            StaffOfferRun refused = runRepository.save(attempt
                    .status(StaffOfferRunStatus.REFUSED)
                    .reason(gate.refusal())
                    .reconciliationId(gate.reconciliation() == null ? null : gate.reconciliation().getId())
                    .build());
            log.warn("Staff offer run {} for the cycle of {} refused: {}", refused.getId(), refused.getCycleStart(),
                    gate.refusal());
            audit(REFUSED, refused, startedBy, "cycle:" + refused.getCycleStart() + ";trigger:" + trigger
                    + ";reason:" + gate.refusal());
            return StaffOfferRunResponse.of(refused);
        }

        // First, while nothing is loaded yet: the bulk update clears the persistence context.
        int expired = offerRepository.expireDue(now, StaffOfferStatus.ACTIVE, StaffOfferStatus.EXPIRED);
        StaffOfferRun run = runRepository.save(attempt
                .status(StaffOfferRunStatus.COMPLETED)
                .reconciliationId(gate.reconciliation().getId())
                .registerMembers(0).ineligible(0).excludedActiveLoan(0).excludedArrears(0).excludedByReconciliation(0)
                .excludedByOverride(0).eligible(0).offered(0).refreshed(0).alreadyOffered(0).withdrawn(0).expired(0)
                .build());

        List<StaffMember> members = memberRepository.findAll(Sort.by("employeeNumber"));
        Map<String, StaffGradeLimit> limits = gradeLimitService.limitsOn(today);
        Map<Long, StaffOffer> open = offerRepository.findByStatus(StaffOfferStatus.ACTIVE).stream()
                .collect(Collectors.toMap(StaffOffer::getStaffMemberId, Function.identity()));
        Set<Long> offeredThisCycle = offerRepository.findMemberIdsByCycleStart(run.getCycleStart());
        Set<String> flagged = flagged(gate.reconciliation());
        Map<Long, StaffLimitOverride> overrides = overrideRepository.findByStatus(StaffLimitOverrideStatus.APPROVED)
                .stream().collect(Collectors.toMap(StaffLimitOverride::getStaffMemberId, Function.identity()));
        List<StaffMember> eligibleByRegister = members.stream()
                .filter(member -> StaffMemberResponse.ineligibleReason(member, limits.get(member.getGrade())) == null)
                .toList();
        Map<Long, Set<StaffLoanStanding.Standing>> standings = loanStanding.of(eligibleByRegister);
        Map<Long, StaffArrearsOverride> arrearsOverrides = arrearsOverrideRepository
                .findByStatus(StaffArrearsOverrideStatus.APPROVED).stream()
                .filter(candidate -> candidate.inForceOn(today))
                .collect(Collectors.toMap(StaffArrearsOverride::getStaffMemberId, Function.identity()));

        Counts counts = new Counts();
        List<StaffOffer> issue = new ArrayList<>();
        for (StaffMember member : members) {
            StaffOffer held = open.get(member.getId());
            StaffGradeLimit limit = limits.get(member.getGrade());
            StaffLimitOverride override = Optional.ofNullable(overrides.get(member.getId()))
                    .filter(candidate -> candidate.appliesTo(member)).orElse(null);
            StaffOfferVerdict verdict = classify(member, limit, standings.getOrDefault(member.getId(), Set.of()),
                    flagged, gate.reconciliation(), override, arrearsOverrides.get(member.getId()));
            if (verdict != StaffOfferVerdict.ELIGIBLE) {
                counts.count(verdict);
                if (held != null) {
                    held.close(StaffOfferStatus.WITHDRAWN, now,
                            reason(verdict, member, limit, gate.reconciliation(), override));
                    counts.withdrawn++;
                }
                continue;
            }
            if (offeredThisCycle.contains(member.getId())) {
                counts.alreadyOffered++;
                continue;
            }
            if (held != null) {
                held.close(StaffOfferStatus.SUPERSEDED, now, null);
                counts.refreshed++;
            } else {
                counts.offered++;
            }
            issue.add(StaffOffer.builder()
                    .staffMemberId(member.getId())
                    .runId(run.getId())
                    .cycleStart(run.getCycleStart())
                    .grade(member.getGrade())
                    .scoreBand(limit.scoreBand())
                    .gradeLimitChangeId(limit.changeId())
                    .amount(override == null ? limit.maximumLimit() : override.getAmount())
                    .limitOverrideId(override == null ? null : override.getId())
                    .issuedAt(now)
                    .expiresAt(now.plusDays(properties.getValidityDays()))
                    .replacesOfferId(held == null ? null : held.getId())
                    .status(StaffOfferStatus.ACTIVE)
                    .build());
        }
        // The closed offers are written before the new ones go in: a member may hold only one ACTIVE offer.
        offerRepository.flush();
        offerRepository.saveAll(issue);
        // Each member just offered is told, in-app at once and by SMS once this commits (FR-SGL-019).
        notificationService.notifyOffers(issue, members.stream()
                .collect(Collectors.toMap(StaffMember::getId, Function.identity())));

        run.setRegisterMembers(members.size());
        run.setIneligible(counts.ineligible);
        run.setExcludedActiveLoan(counts.excludedActiveLoan);
        run.setExcludedArrears(counts.excludedArrears);
        run.setExcludedByReconciliation(counts.excludedByReconciliation);
        run.setExcludedByOverride(counts.excludedByOverride);
        run.setEligible(counts.offered + counts.refreshed + counts.alreadyOffered);
        run.setOffered(counts.offered);
        run.setRefreshed(counts.refreshed);
        run.setAlreadyOffered(counts.alreadyOffered);
        run.setWithdrawn(counts.withdrawn);
        run.setExpired(expired);
        run.setFinishedAt(marketTimeZone.nowUtc());
        runRepository.save(run);
        log.info("Staff offer run {} for the cycle of {} ({}, by {}): {} members, {} eligible, {} offered, {} refreshed,"
                        + " {} already offered, {} ineligible, {} excluded (active loan {}, arrears {}, reconciliation"
                        + " {}, override {}), {} withdrawn, {} expired", run.getId(), run.getCycleStart(), trigger,
                startedBy, run.getRegisterMembers(), run.getEligible(), run.getOffered(), run.getRefreshed(),
                run.getAlreadyOffered(), run.getIneligible(), counts.excludedActiveLoan + counts.excludedArrears
                        + counts.excludedByReconciliation + counts.excludedByOverride,
                counts.excludedActiveLoan, counts.excludedArrears, counts.excludedByReconciliation,
                counts.excludedByOverride, run.getWithdrawn(), run.getExpired());
        audit(COMPLETED, run, startedBy, "cycle:" + run.getCycleStart() + ";trigger:" + trigger + ";reconciliation:"
                + run.getReconciliationId() + ";members:" + run.getRegisterMembers() + ";eligible:" + run.getEligible()
                + ";offered:" + run.getOffered() + ";refreshed:" + run.getRefreshed() + ";alreadyOffered:"
                + run.getAlreadyOffered() + ";ineligible:" + run.getIneligible() + ";excludedActiveLoan:"
                + run.getExcludedActiveLoan() + ";excludedArrears:" + run.getExcludedArrears()
                + ";excludedByReconciliation:" + run.getExcludedByReconciliation() + ";excludedByOverride:"
                + run.getExcludedByOverride() + ";withdrawn:"
                + run.getWithdrawn() + ";expired:" + run.getExpired());
        return StaffOfferRunResponse.of(run);
    }

    /**
     * Where {@code member} stands for an offer right now: the verdict the run would reach, the amount it would offer,
     * and whether offers can be made at all. What the SuperApp shows before a borrower applies, and what is checked
     * again when they accept (FR-SGL-013).
     */
    @Transactional(readOnly = true)
    public StaffOfferAssessment assess(StaffMember member) {
        LocalDate today = marketTimeZone.today();
        return assess(member, today, gate(today));
    }

    /**
     * A borrower applying in the SuperApp without an offer in hand (FR-SGL-025): the offer they already hold, or, when
     * they hold none and the run would offer them one, a new one made now, on the run's terms: their grade's limit or
     * Credit's override, valid for {@code validity-days}. It belongs to no run and is not notified, since they are in
     * the app. Under the register's lock, like a run, so no approval or run changes them meanwhile.
     *
     * @throws NotFoundException no such member
     */
    @Transactional
    public StaffOfferApplication apply(Long staffMemberId) {
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        StaffMember member = memberRepository.findById(staffMemberId)
                .orElseThrow(() -> new NotFoundException("Staff member " + staffMemberId + " not found"));
        LocalDateTime now = marketTimeZone.nowUtc();
        LocalDate today = marketTimeZone.today();
        Gate gate = gate(today);
        StaffOfferAssessment assessment = assess(member, today, gate);
        if (assessment.verdict() != StaffOfferVerdict.ELIGIBLE) {
            return StaffOfferApplication.declined(assessment.verdict());
        }
        Optional<StaffOffer> held = offerRepository.findByStaffMemberIdAndStatus(member.getId(),
                StaffOfferStatus.ACTIVE);
        if (held.isPresent() && held.get().isOpenAt(now)) {
            return StaffOfferApplication.held(held.get());
        }
        if (gate.refusal() != null) {
            log.warn("Staff member {} applied for a Staff Grocery Loan, but no offer can be made: {}",
                    member.getEmployeeNumber(), gate.refusal());
            return StaffOfferApplication.unavailable(gate.refusal());
        }
        held.ifPresent(lapsed -> {
            // Past its expiry and not yet closed by a run: closed as it would have been, at its expiry.
            lapsed.close(StaffOfferStatus.EXPIRED, lapsed.getExpiresAt(), null);
            offerRepository.flush();
        });
        StaffOffer offer = offerRepository.save(StaffOffer.builder()
                .staffMemberId(member.getId())
                .origin(StaffOfferOrigin.APPLY)
                .cycleStart(cycleOf(today))
                .grade(member.getGrade())
                .scoreBand(assessment.limit().scoreBand())
                .gradeLimitChangeId(assessment.limit().changeId())
                .amount(assessment.amount())
                .limitOverrideId(assessment.override() == null ? null : assessment.override().getId())
                .issuedAt(now)
                .expiresAt(now.plusDays(properties.getValidityDays()))
                .status(StaffOfferStatus.ACTIVE)
                .build());
        log.info("Staff member {} applied in the SuperApp: offer {} made for {}", member.getEmployeeNumber(),
                offer.getId(), offer.getAmount());
        auditService.record(AuditLog.builder()
                .eventType(APPLIED)
                .entityType("STAFF_OFFER").entityId(String.valueOf(offer.getId()))
                .actorId(JwtService.BORROWER_USERNAME_PREFIX + member.getEmployeeNumber()).channelUsed("superapp")
                .detail("amount:" + offer.getAmount() + ";cycle:" + offer.getCycleStart()
                        + (offer.getLimitOverrideId() == null ? "" : ";limitOverride:" + offer.getLimitOverrideId())));
        return StaffOfferApplication.made(offer);
    }

    private StaffOfferAssessment assess(StaffMember member, LocalDate today, Gate gate) {
        StaffGradeLimit limit = gradeLimitService.limitOn(member.getGrade(), today).orElse(null);
        StaffLimitOverride override = overrideRepository
                .findByStaffMemberIdAndStatus(member.getId(), StaffLimitOverrideStatus.APPROVED)
                .filter(candidate -> candidate.appliesTo(member)).orElse(null);
        Set<StaffLoanStanding.Standing> standing = loanStanding.of(List.of(member))
                .getOrDefault(member.getId(), Set.of());
        StaffArrearsOverride arrearsOverride = arrearsOverrideRepository
                .findByStaffMemberIdAndStatus(member.getId(), StaffArrearsOverrideStatus.APPROVED)
                .filter(candidate -> candidate.inForceOn(today)).orElse(null);
        Set<String> flagged = gate.reconciliation() == null ? Set.of() : flagged(gate.reconciliation());
        StaffOfferVerdict verdict = classify(member, limit, standing, flagged, gate.reconciliation(), override,
                arrearsOverride);
        // Named only when it is what lets them borrow, so that the loan they accept uses it up.
        boolean lifted = verdict == StaffOfferVerdict.ELIGIBLE
                && standing.contains(StaffLoanStanding.Standing.WRITTEN_OFF);
        return new StaffOfferAssessment(verdict, gate.refusal(), limit, override, lifted ? arrearsOverride : null);
    }

    /**
     * Records a run that broke off. Its own transaction: the run's was rolled back, and nothing it did was kept.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StaffOfferRunResponse recordFailure(StaffOfferRunTrigger trigger, String startedBy, LocalDateTime startedAt,
                                               RuntimeException failure) {
        String reason = StringUtils.abbreviate("The run failed and nothing it did was kept: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage(), MAX_REASON);
        StaffOfferRun failed = runRepository.save(StaffOfferRun.builder()
                .cycleStart(cycleOf(marketTimeZone.localDay(startedAt)))
                .trigger(trigger)
                .startedBy(startedBy)
                .startedAt(startedAt)
                .finishedAt(marketTimeZone.nowUtc())
                .status(StaffOfferRunStatus.FAILED)
                .reason(reason)
                .build());
        audit(FAILED, failed, startedBy, "cycle:" + failed.getCycleStart() + ";trigger:" + trigger + ";failure:"
                + failure.getClass().getSimpleName());
        return StaffOfferRunResponse.of(failed);
    }

    /** Run attempts, newest first. */
    @Transactional(readOnly = true)
    public Page<StaffOfferRunResponse> runs(Pageable pageable) {
        return runRepository.findAll(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "id")))
                .map(StaffOfferRunResponse::of);
    }

    /** @throws NotFoundException no such run */
    @Transactional(readOnly = true)
    public StaffOfferRunResponse run(Long runId) {
        return StaffOfferRunResponse.of(runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("Staff offer run " + runId + " not found")));
    }

    /** The run's settings, when it next fires, whether it fires by itself, and whether a run now would go ahead. */
    @Transactional(readOnly = true)
    public StaffOfferScheduleResponse schedule() {
        LocalDate today = marketTimeZone.today();
        Gate gate = gate(today);
        StaffRegisterReconciliation latest = gate.reconciliation();
        LocalDateTime next = Optional.ofNullable(CronExpression.parse(properties.getRunCron()).next(marketTimeZone.now()))
                .map(at -> at.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())
                .orElse(null);
        return new StaffOfferScheduleResponse(properties.getRunCron(), marketTimeZone.zone().getId(), next,
                jobsSwitch.on(),
                properties.getValidityDays(), properties.getMaxReconciliationAgeDays(),
                latest == null ? null : latest.getId(), latest == null ? null : latest.getRunAt(),
                latest == null ? null : gate.ageDays(), gate.refusal() == null, gate.refusal(),
                runRepository.findFirstByOrderByIdDesc().map(StaffOfferRunResponse::of).orElse(null));
    }

    /** The cycle a market day belongs to: the Monday of its week. */
    static LocalDate cycleOf(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** The latest reconciliation, and why it does not allow a run, if it does not. */
    private record Gate(StaffRegisterReconciliation reconciliation, long ageDays, String refusal) {
    }

    private Gate gate(LocalDate today) {
        Optional<StaffRegisterReconciliation> latest = reconciliationRepository.findFirstByOrderByIdDesc();
        if (latest.isEmpty()) {
            return new Gate(null, 0, NEVER_RECONCILED);
        }
        LocalDate reconciledOn = marketTimeZone.localDay(latest.get().getRunAt());
        long age = ChronoUnit.DAYS.between(reconciledOn, today);
        if (age > properties.getMaxReconciliationAgeDays()) {
            return new Gate(latest.get(), age, String.format("The staff register was last reconciled against the"
                            + " payroll master on %s, %d days ago; offers need a reconciliation within the last %d"
                            + " days", reconciledOn, age, properties.getMaxReconciliationAgeDays()));
        }
        return new Gate(latest.get(), age, null);
    }

    /** The employee numbers the reconciliation lists as having left or not on the payroll. */
    private Set<String> flagged(StaffRegisterReconciliation reconciliation) {
        return new HashSet<>(varianceRepository.findEmployeeNumbers(reconciliation.getId(),
                List.of(StaffRegisterVarianceKind.LEFT_ON_PAYROLL, StaffRegisterVarianceKind.NOT_ON_PAYROLL)));
    }

    /**
     * Whether {@code member} may be offered, and if not why: the one rule for the run, a borrower applying, and one
     * accepting. {@code standing} is their Staff Grocery Loan standing (empty when clear), {@code flagged} the employee
     * numbers the latest reconciliation lists as having left or not on the payroll, {@code override} Credit's limit
     * override in force for them, and {@code arrearsOverride} Credit's arrears override in force for them, if any.
     *
     * <p>The arrears override lifts a written-off balance only (FR-SGL-014). A loan that is overdue is still open, and
     * nobody may hold a second one beside it (FR-SGL-013), override or not.</p>
     */
    static StaffOfferVerdict classify(StaffMember member, StaffGradeLimit limit,
                                      Set<StaffLoanStanding.Standing> standing, Set<String> flagged,
                                      StaffRegisterReconciliation reconciliation, StaffLimitOverride override,
                                      StaffArrearsOverride arrearsOverride) {
        if (member.getEmploymentStatus() != StaffEmploymentStatus.ACTIVE) {
            return StaffOfferVerdict.NOT_ACTIVE;
        }
        if (limit == null || !limit.lends()) {
            return StaffOfferVerdict.NO_LIMIT;
        }
        if (standing.contains(StaffLoanStanding.Standing.ARREARS)
                || standing.contains(StaffLoanStanding.Standing.WRITTEN_OFF) && arrearsOverride == null) {
            return StaffOfferVerdict.ARREARS;
        }
        if (standing.contains(StaffLoanStanding.Standing.ACTIVE_LOAN)) {
            return StaffOfferVerdict.ACTIVE_LOAN;
        }
        // A record Human Capital has changed since the reconciliation is taken as dealt with.
        if (reconciliation != null && flagged.contains(member.getEmployeeNumber())
                && !member.getUpdatedAt().isAfter(reconciliation.getRunAt())) {
            return StaffOfferVerdict.PAYROLL_FLAGGED;
        }
        if (override != null && override.blocks()) {
            return StaffOfferVerdict.LIMIT_ZERO;
        }
        return StaffOfferVerdict.ELIGIBLE;
    }

    /** Why a member is not offered, as the run records it on an offer it withdraws. */
    private static String reason(StaffOfferVerdict verdict, StaffMember member, StaffGradeLimit limit,
                                 StaffRegisterReconciliation reconciliation, StaffLimitOverride override) {
        return switch (verdict) {
            case NOT_ACTIVE, NO_LIMIT -> "No longer eligible: " + StaffMemberResponse.ineligibleReason(member, limit);
            case ARREARS -> "In arrears on a Staff Grocery Loan, or owes a written-off one";
            case ACTIVE_LOAN -> "Holds an active Staff Grocery Loan";
            case PAYROLL_FLAGGED -> "Payroll reconciliation " + reconciliation.getId() + " lists them as having left or"
                    + " not on the payroll";
            case LIMIT_ZERO -> "Credit set their limit to 0 (limit override " + override.getId() + ")";
            case ELIGIBLE -> throw new IllegalArgumentException("An eligible member is not withdrawn from");
        };
    }

    private static final class Counts {
        int ineligible;
        int excludedActiveLoan;
        int excludedArrears;
        int excludedByReconciliation;
        int excludedByOverride;
        int offered;
        int refreshed;
        int alreadyOffered;
        int withdrawn;

        void count(StaffOfferVerdict verdict) {
            switch (verdict) {
                case NOT_ACTIVE, NO_LIMIT -> ineligible++;
                case ARREARS -> excludedArrears++;
                case ACTIVE_LOAN -> excludedActiveLoan++;
                case PAYROLL_FLAGGED -> excludedByReconciliation++;
                case LIMIT_ZERO -> excludedByOverride++;
                case ELIGIBLE -> throw new IllegalArgumentException("An eligible member is counted as offered");
            }
        }
    }

    private void audit(String eventType, StaffOfferRun run, String actor, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(eventType)
                .entityType(ENTITY).entityId(String.valueOf(run.getId()))
                .actorId(actor).channelUsed("admin-portal")
                .detail(detail));
    }
}
