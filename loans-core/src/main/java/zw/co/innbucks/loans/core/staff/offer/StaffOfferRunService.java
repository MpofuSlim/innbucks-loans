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
import zw.co.innbucks.loans.core.staff.StaffGradeLimit;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitService;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffMemberResponse;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliation;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliationRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.StaffRegisterVarianceKind;
import zw.co.innbucks.loans.core.staff.StaffRegisterVarianceRepository;

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
 * ACTIVE, or their grade has no limit above zero today), excluded (an active loan or arrears under this product, or
 * flagged by the reconciliation), or eligible. An eligible member without this cycle's offer gets one at their grade's
 * limit, valid for {@code validity-days}, replacing any offer they still hold; an ineligible or excluded member's open
 * offer is withdrawn with the reason.</p>
 *
 * <p><b>Re-running.</b> A run belongs to a cycle, the market week it falls in, and a member gets at most one offer per
 * cycle (the database enforces it). A whole run is one transaction under two locks: one that refuses a second run
 * while one is in progress, and the register's lock, so no approval changes the register mid-run. So a run that fails
 * leaves nothing behind, and running again in the same week only tops up the members still without an offer, such as
 * those added to the register since.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffOfferRunService {

    static final String COMPLETED = "STAFF_OFFER_RUN_COMPLETED";
    static final String REFUSED = "STAFF_OFFER_RUN_REFUSED";
    static final String FAILED = "STAFF_OFFER_RUN_FAILED";
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
    private final StaffOfferProperties properties;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

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
                .eligible(0).offered(0).refreshed(0).alreadyOffered(0).withdrawn(0).expired(0)
                .build());

        List<StaffMember> members = memberRepository.findAll(Sort.by("employeeNumber"));
        Map<String, StaffGradeLimit> limits = gradeLimitService.limitsOn(today);
        Map<Long, StaffOffer> open = offerRepository.findByStatus(StaffOfferStatus.ACTIVE).stream()
                .collect(Collectors.toMap(StaffOffer::getStaffMemberId, Function.identity()));
        Set<Long> offeredThisCycle = offerRepository.findMemberIdsByCycleStart(run.getCycleStart());
        Set<String> flagged = flagged(gate.reconciliation());
        List<StaffMember> eligibleByRegister = members.stream()
                .filter(member -> StaffMemberResponse.ineligibleReason(member, limits.get(member.getGrade())) == null)
                .toList();
        Map<Long, StaffLoanStanding.Standing> standings = loanStanding.of(eligibleByRegister);

        Counts counts = new Counts();
        List<StaffOffer> issue = new ArrayList<>();
        for (StaffMember member : members) {
            StaffOffer held = open.get(member.getId());
            StaffGradeLimit limit = limits.get(member.getGrade());
            String ineligible = StaffMemberResponse.ineligibleReason(member, limit);
            String excluded = ineligible != null ? null
                    : exclusion(member, standings.get(member.getId()), flagged, gate.reconciliation(), counts);
            if (ineligible != null || excluded != null) {
                if (ineligible != null) {
                    counts.ineligible++;
                }
                if (held != null) {
                    held.close(StaffOfferStatus.WITHDRAWN, now,
                            ineligible != null ? "No longer eligible: " + ineligible : excluded);
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
                    .amount(limit.maximumLimit())
                    .issuedAt(now)
                    .expiresAt(now.plusDays(properties.getValidityDays()))
                    .replacesOfferId(held == null ? null : held.getId())
                    .status(StaffOfferStatus.ACTIVE)
                    .build());
        }
        // The closed offers are written before the new ones go in: a member may hold only one ACTIVE offer.
        offerRepository.flush();
        offerRepository.saveAll(issue);

        run.setRegisterMembers(members.size());
        run.setIneligible(counts.ineligible);
        run.setExcludedActiveLoan(counts.excludedActiveLoan);
        run.setExcludedArrears(counts.excludedArrears);
        run.setExcludedByReconciliation(counts.excludedByReconciliation);
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
                        + " {}), {} withdrawn, {} expired", run.getId(), run.getCycleStart(), trigger, startedBy,
                run.getRegisterMembers(), run.getEligible(), run.getOffered(), run.getRefreshed(),
                run.getAlreadyOffered(), run.getIneligible(),
                counts.excludedActiveLoan + counts.excludedArrears + counts.excludedByReconciliation,
                counts.excludedActiveLoan, counts.excludedArrears, counts.excludedByReconciliation,
                run.getWithdrawn(), run.getExpired());
        audit(COMPLETED, run, startedBy, "cycle:" + run.getCycleStart() + ";trigger:" + trigger + ";reconciliation:"
                + run.getReconciliationId() + ";members:" + run.getRegisterMembers() + ";eligible:" + run.getEligible()
                + ";offered:" + run.getOffered() + ";refreshed:" + run.getRefreshed() + ";alreadyOffered:"
                + run.getAlreadyOffered() + ";ineligible:" + run.getIneligible() + ";excludedActiveLoan:"
                + run.getExcludedActiveLoan() + ";excludedArrears:" + run.getExcludedArrears()
                + ";excludedByReconciliation:" + run.getExcludedByReconciliation() + ";withdrawn:"
                + run.getWithdrawn() + ";expired:" + run.getExpired());
        return StaffOfferRunResponse.of(run);
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

    /** The run's settings, when it next fires, and whether a run now would go ahead. */
    @Transactional(readOnly = true)
    public StaffOfferScheduleResponse schedule() {
        LocalDate today = marketTimeZone.today();
        Gate gate = gate(today);
        StaffRegisterReconciliation latest = gate.reconciliation();
        LocalDateTime next = Optional.ofNullable(CronExpression.parse(properties.getRunCron()).next(marketTimeZone.now()))
                .map(at -> at.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())
                .orElse(null);
        return new StaffOfferScheduleResponse(properties.getRunCron(), marketTimeZone.zone().getId(), next,
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

    /** Why an eligible member is not offered, counted; null when they are. */
    private static String exclusion(StaffMember member, StaffLoanStanding.Standing standing, Set<String> flagged,
                                    StaffRegisterReconciliation reconciliation, Counts counts) {
        if (standing == StaffLoanStanding.Standing.ARREARS) {
            counts.excludedArrears++;
            return "In arrears on a Staff Grocery Loan";
        }
        if (standing == StaffLoanStanding.Standing.ACTIVE_LOAN) {
            counts.excludedActiveLoan++;
            return "Holds an active Staff Grocery Loan";
        }
        // A record Human Capital has changed since the reconciliation is taken as dealt with.
        if (flagged.contains(member.getEmployeeNumber()) && !member.getUpdatedAt().isAfter(reconciliation.getRunAt())) {
            counts.excludedByReconciliation++;
            return "Payroll reconciliation " + reconciliation.getId() + " lists them as having left or not on the"
                    + " payroll";
        }
        return null;
    }

    private static final class Counts {
        int ineligible;
        int excludedActiveLoan;
        int excludedArrears;
        int excludedByReconciliation;
        int offered;
        int refreshed;
        int alreadyOffered;
        int withdrawn;
    }

    private void audit(String eventType, StaffOfferRun run, String actor, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(eventType)
                .entityType(ENTITY).entityId(String.valueOf(run.getId()))
                .actorId(actor).channelUsed("admin-portal")
                .detail(detail));
    }
}
