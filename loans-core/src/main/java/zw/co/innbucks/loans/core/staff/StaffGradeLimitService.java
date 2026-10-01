package zw.co.innbucks.loans.core.staff;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Staff Grocery Loan grade-to-limit matrix (FR-SGL-009, FR-SGL-010). Each grade maps to one maximum loan
 * amount and a score band, from an effective market date. The matrix is the credit decision for this product: a
 * deterministic lookup, so it changes only under maker-checker. One user proposes a change; another approves or
 * rejects it; the proposer may withdraw it until then.
 *
 * <p>Effective dating keeps the past fixed. A change applies from today or later, never earlier, and a change whose
 * date passed while it waited cannot be approved. A limit already in force is never rewritten: to change it, approve a
 * limit from a later day. An approved limit not yet in force can be replaced by approving another for the same grade
 * and date, which marks the first SUPERSEDED. Loans keep the amount they were booked for, so no change to the matrix
 * reaches a loan already booked.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffGradeLimitService {

    static final String PROPOSED = "STAFF_GRADE_LIMIT_PROPOSED";
    static final String APPROVED = "STAFF_GRADE_LIMIT_APPROVED";
    static final String REJECTED = "STAFF_GRADE_LIMIT_REJECTED";
    static final String WITHDRAWN = "STAFF_GRADE_LIMIT_WITHDRAWN";
    static final String SUPERSEDED = "STAFF_GRADE_LIMIT_SUPERSEDED";
    private static final String ENTITY = "STAFF_GRADE_LIMIT_CHANGE";
    private static final String CHANNEL = "admin-portal";

    private final StaffGradeLimitChangeRepository repository;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * The matrix on a market day: every grade with an approved limit or a pending proposal, alphabetically, with the
     * limit in force that day, the approved limits still to come and how many proposals wait.
     */
    @Transactional(readOnly = true)
    public List<StaffGradeLimitResponse> matrix(LocalDate asOf) {
        LocalDate day = asOf == null ? marketTimeZone.today() : asOf;
        Map<String, List<StaffGradeLimitChange>> approved = new TreeMap<>();
        Map<String, Integer> pending = new TreeMap<>();
        for (StaffGradeLimitChange change : repository.findByStatusIn(
                EnumSet.of(StaffGradeLimitChangeStatus.APPROVED, StaffGradeLimitChangeStatus.PENDING))) {
            if (change.getStatus() == StaffGradeLimitChangeStatus.APPROVED) {
                approved.computeIfAbsent(change.getGrade(), grade -> new ArrayList<>()).add(change);
            } else {
                pending.merge(change.getGrade(), 1, Integer::sum);
            }
            approved.computeIfAbsent(change.getGrade(), grade -> new ArrayList<>());
        }
        List<StaffGradeLimitResponse> rows = new ArrayList<>();
        approved.forEach((grade, changes) -> {
            changes.sort(Comparator.comparing(StaffGradeLimitChange::getEffectiveFrom));
            StaffGradeLimit current = null;
            List<StaffGradeLimit> scheduled = new ArrayList<>();
            for (StaffGradeLimitChange change : changes) {
                if (change.getEffectiveFrom().isAfter(day)) {
                    scheduled.add(StaffGradeLimit.of(change));
                } else {
                    current = StaffGradeLimit.of(change);
                }
            }
            rows.add(new StaffGradeLimitResponse(grade, current, List.copyOf(scheduled),
                    pending.getOrDefault(grade, 0)));
        });
        return rows;
    }

    /**
     * Changes to the matrix, newest first: the checker's queue ({@code status=PENDING}) and each grade's history.
     * A pending change carries the limit it would replace.
     */
    @Transactional(readOnly = true)
    public List<StaffGradeLimitChangeResponse> changes(String grade, StaffGradeLimitChangeStatus status) {
        String wanted = StringUtils.isBlank(grade) ? null : StaffGrades.normalise(grade);
        List<StaffGradeLimitChange> all = repository.findAll();
        return all.stream()
                .filter(change -> wanted == null || wanted.equals(change.getGrade()))
                .filter(change -> status == null || status == change.getStatus())
                .sorted(Comparator.comparing(StaffGradeLimitChange::getId).reversed())
                .map(change -> StaffGradeLimitChangeResponse.of(change, replacing(change, all)))
                .toList();
    }

    /**
     * Proposes a grade's limit from a day, for a second person to approve.
     *
     * @throws ValidationException the grade is longer than a grade may be, or the effective date is in the past
     * @throws ConflictException   a proposal for the grade and date is already waiting, or the grade's limit from that
     *                             date is already in force
     */
    @Transactional
    public StaffGradeLimitChangeResponse propose(ProposeStaffGradeLimitRequest request) {
        if (!StaffGrades.valid(request.getGrade())) {
            throw new ValidationException(StaffGrades.MESSAGE);
        }
        String grade = StaffGrades.normalise(request.getGrade());
        LocalDate from = request.getEffectiveFrom();
        LocalDate today = marketTimeZone.today();
        if (from.isBefore(today)) {
            throw new ValidationException(String.format(
                    "A grade limit cannot apply from %s, which has passed; the earliest is today, %s", from, today));
        }
        repository.findByGradeAndEffectiveFromAndStatus(grade, from, StaffGradeLimitChangeStatus.PENDING)
                .ifPresent(waiting -> {
                    throw new ConflictException(String.format("A change to grade %s from %s is already waiting for"
                            + " approval (change %d); approve, reject or withdraw it first", grade, from,
                            waiting.getId()));
                });
        repository.findByGradeAndEffectiveFromAndStatus(grade, from, StaffGradeLimitChangeStatus.APPROVED)
                .filter(approved -> !approved.getEffectiveFrom().isAfter(today))
                .ifPresent(inForce -> {
                    throw inForce(grade, from);
                });
        String username = authService.getLoggedInUsername();
        StaffGradeLimitChange change;
        try {
            change = repository.saveAndFlush(StaffGradeLimitChange.builder()
                    .grade(grade)
                    .scoreBand(request.getScoreBand().strip())
                    .maximumLimit(money(request.getMaximumLimit()))
                    .effectiveFrom(from)
                    .status(StaffGradeLimitChangeStatus.PENDING)
                    .proposedBy(username)
                    .proposedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .proposalComment(StringUtils.trimToNull(request.getComment()))
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // Someone else proposed a change to the grade from the same day at the same moment.
            throw new ConflictException(String.format("A change to grade %s from %s is already waiting for"
                    + " approval; approve, reject or withdraw it first", grade, from));
        }
        log.info("Grade limit change {} proposed by {}: {}", change.getId(), username, describe(change));
        audit(PROPOSED, change, username, describe(change));
        return StaffGradeLimitChangeResponse.of(change, replacing(change, repository.findAll()));
    }

    /**
     * Approves or rejects a proposed change. Never by whoever proposed it.
     *
     * @throws NotFoundException     no such change
     * @throws ConflictException     it is no longer pending; or, to approve: its date has passed, or the grade's limit
     *                               from that date came into force while it waited
     * @throws AccessDeniedException the caller proposed it
     * @throws ValidationException   a rejection without a reason
     */
    @Transactional
    public StaffGradeLimitChangeResponse decide(Long id, StaffGradeLimitDecisionRequest request) {
        StaffGradeLimitChange change = pendingForUpdate(id);
        String username = authService.getLoggedInUsername();
        if (StringUtils.equalsIgnoreCase(username, change.getProposedBy())) {
            throw new AccessDeniedException(String.format("%s proposed grade limit change %d and cannot also"
                    + " approve or reject it; another credit manager or SUPER_ADMIN must", username, id));
        }
        String comment = StringUtils.trimToNull(request.getComment());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (request.getDecision() == StaffGradeLimitDecision.REJECTED) {
            if (comment == null) {
                throw new ValidationException("A reason is required to reject a grade limit change");
            }
            settle(change, StaffGradeLimitChangeStatus.REJECTED, username, now, comment);
            log.info("Grade limit change {} rejected by {}: {}", id, username, describe(change));
            audit(REJECTED, change, username, describe(change) + ";reason:" + comment);
            return StaffGradeLimitChangeResponse.of(change, null);
        }
        LocalDate today = marketTimeZone.today();
        if (change.getEffectiveFrom().isBefore(today)) {
            throw new ConflictException(String.format("Grade limit change %d was to apply from %s, which has passed;"
                    + " reject it and propose it again from today or later", id, change.getEffectiveFrom()));
        }
        Optional<StaffGradeLimitChange> replaced = repository.findForUpdate(change.getGrade(),
                change.getEffectiveFrom(), StaffGradeLimitChangeStatus.APPROVED);
        if (replaced.isPresent()) {
            StaffGradeLimitChange earlier = replaced.get();
            if (!earlier.getEffectiveFrom().isAfter(today)) {
                throw inForce(change.getGrade(), change.getEffectiveFrom());
            }
            earlier.setStatus(StaffGradeLimitChangeStatus.SUPERSEDED);
            earlier.setSupersededBy(change.getId());
            earlier.setSupersededAt(now);
            // Out of the approved index before this change goes into it.
            repository.saveAndFlush(earlier);
            log.info("Grade limit change {} superseded by {} before it came into force: {}", earlier.getId(), id,
                    describe(earlier));
            audit(SUPERSEDED, earlier, username, describe(earlier) + ";supersededBy:" + id);
        }
        settle(change, StaffGradeLimitChangeStatus.APPROVED, username, now, comment);
        log.info("Grade limit change {} approved by {}: {}", id, username, describe(change));
        audit(APPROVED, change, username, describe(change)
                + replaced.map(earlier -> ";supersedes:" + earlier.getId()).orElse(""));
        return StaffGradeLimitChangeResponse.of(change, null);
    }

    /**
     * Takes back a proposal before anyone decides it. Only whoever proposed it may.
     *
     * @throws NotFoundException     no such change
     * @throws ConflictException     it is no longer pending
     * @throws AccessDeniedException the caller did not propose it
     */
    @Transactional
    public StaffGradeLimitChangeResponse withdraw(Long id) {
        StaffGradeLimitChange change = pendingForUpdate(id);
        String username = authService.getLoggedInUsername();
        if (!StringUtils.equals(username, change.getProposedBy())) {
            throw new AccessDeniedException(String.format("Only %s, who proposed grade limit change %d, can withdraw"
                    + " it; anyone else approves or rejects it", change.getProposedBy(), id));
        }
        settle(change, StaffGradeLimitChangeStatus.WITHDRAWN, username, LocalDateTime.now(ZoneOffset.UTC), null);
        log.info("Grade limit change {} withdrawn by {}: {}", id, username, describe(change));
        audit(WITHDRAWN, change, username, describe(change));
        return StaffGradeLimitChangeResponse.of(change, null);
    }

    /** The approved limit in force for the grade on the market day, if it has one. */
    @Transactional(readOnly = true)
    public Optional<StaffGradeLimit> limitOn(String grade, LocalDate day) {
        if (StringUtils.isBlank(grade)) {
            return Optional.empty();
        }
        return repository.findFirstByGradeAndStatusAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        StaffGrades.normalise(grade), StaffGradeLimitChangeStatus.APPROVED, day)
                .map(StaffGradeLimit::of);
    }

    /** Whether the matrix knows the grade: it has an approved limit, in force or still to come. */
    @Transactional(readOnly = true)
    public boolean recognises(String grade) {
        return StringUtils.isNotBlank(grade)
                && repository.existsByGradeAndStatus(StaffGrades.normalise(grade), StaffGradeLimitChangeStatus.APPROVED);
    }

    /** Every grade the matrix knows, for checking a whole file in one query. */
    @Transactional(readOnly = true)
    public Set<String> recognisedGrades() {
        return Set.copyOf(repository.findGradesByStatus(StaffGradeLimitChangeStatus.APPROVED));
    }

    /** The approved limit in force on the market day for every grade that has one. */
    @Transactional(readOnly = true)
    public Map<String, StaffGradeLimit> limitsOn(LocalDate day) {
        Map<String, StaffGradeLimit> limits = new TreeMap<>();
        for (StaffGradeLimitResponse row : matrix(day)) {
            if (row.current() != null) {
                limits.put(row.grade(), row.current());
            }
        }
        return limits;
    }

    private StaffGradeLimitChange pendingForUpdate(Long id) {
        StaffGradeLimitChange change = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Grade limit change " + id + " not found"));
        if (change.getStatus() != StaffGradeLimitChangeStatus.PENDING) {
            throw new ConflictException(String.format("Grade limit change %d is already %s", id,
                    change.getStatus().name().toLowerCase()));
        }
        return change;
    }

    private void settle(StaffGradeLimitChange change, StaffGradeLimitChangeStatus status, String username,
                        LocalDateTime at, String comment) {
        change.setStatus(status);
        change.setDecidedBy(username);
        change.setDecidedAt(at);
        change.setDecisionComment(comment);
        repository.save(change);
    }

    /** For a pending change: the approved limit the grade would otherwise have on its effective date. */
    private static StaffGradeLimit replacing(StaffGradeLimitChange change, List<StaffGradeLimitChange> all) {
        if (change.getStatus() != StaffGradeLimitChangeStatus.PENDING) {
            return null;
        }
        return all.stream()
                .filter(other -> other.getStatus() == StaffGradeLimitChangeStatus.APPROVED)
                .filter(other -> other.getGrade().equals(change.getGrade()))
                .filter(other -> !other.getEffectiveFrom().isAfter(change.getEffectiveFrom()))
                .max(Comparator.comparing(StaffGradeLimitChange::getEffectiveFrom))
                .map(StaffGradeLimit::of)
                .orElse(null);
    }

    private static ConflictException inForce(String grade, LocalDate from) {
        return new ConflictException(String.format("Grade %s's limit from %s is already in force and cannot be"
                + " replaced; propose a change from a later day", grade, from));
    }

    /** Money to the cent, as the column holds it; validation already refused more decimal places. */
    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String describe(StaffGradeLimitChange change) {
        return "grade:" + change.getGrade() + ";scoreBand:" + change.getScoreBand() + ";maximumLimit:"
                + change.getMaximumLimit().toPlainString() + ";effectiveFrom:" + change.getEffectiveFrom();
    }

    private void audit(String eventType, StaffGradeLimitChange change, String actor, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(eventType)
                .entityType(ENTITY).entityId(String.valueOf(change.getId()))
                .actorId(actor).channelUsed(CHANNEL)
                .detail(detail));
    }
}
