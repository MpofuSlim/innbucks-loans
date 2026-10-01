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
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverride;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Retiring and renaming grades in the Staff Grocery Loan grade-to-limit matrix (FR-SGL-010). The bank renames, merges
 * and drops grades, so the matrix's grades are managed from the portal like its limits, and under the same
 * maker-checker: a CREDIT_MANAGER or SUPER_ADMIN proposes a change, another one approves or rejects it, and the proposer
 * may withdraw it until then. Every step is audited.
 *
 * <p><b>Retiring</b> takes a grade out of the matrix. Its approved limits become RETIRED, so it no longer offers
 * anything and the register refuses it. It is refused while anyone still employed holds the grade (move them to another
 * grade through the register first), so nobody is left without a limit. Staff who have left keep it on their record. A
 * grade brought back later starts from a limit approved afresh: none of its retired limits applies again.</p>
 *
 * <p><b>Renaming</b> carries the grade over whole, in one transaction: its approved limits are copied to the new name
 * with their dates and its old ones RETIRED; every staff member at the grade moves to the new name, each move recorded
 * in their history against this change; and their pending and approved limit overrides move with them, since an
 * override applies only while its member holds the grade it was set for. Offers and loans keep the grade they were made
 * at. The register is Human Capital's, so its next upload must use the new name: the old one is refused.</p>
 *
 * <p><b>Both</b> are refused while a limit change for the grade waits for approval, or a register batch waiting for
 * approval would put staff at it, because either would land on a grade that is gone. Each is checked when proposed and
 * again when approved, under the register's lock, since the register and the matrix move in between.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffGradeChangeService {

    static final String PROPOSED = "STAFF_GRADE_CHANGE_PROPOSED";
    static final String REJECTED = "STAFF_GRADE_CHANGE_REJECTED";
    static final String WITHDRAWN = "STAFF_GRADE_CHANGE_WITHDRAWN";
    static final String RETIRED = "STAFF_GRADE_RETIRED";
    static final String RENAMED = "STAFF_GRADE_RENAMED";
    private static final String ENTITY = "STAFF_GRADE_CHANGE";
    private static final String CHANNEL = "admin-portal";
    /** Still on the payroll: a grade they hold is not retired from under them. */
    static final Set<StaffEmploymentStatus> EMPLOYED = EnumSet.of(StaffEmploymentStatus.ACTIVE,
            StaffEmploymentStatus.SUSPENDED, StaffEmploymentStatus.UNPAID_LEAVE);
    private static final Set<StaffLimitOverrideStatus> OVERRIDES_MOVED = EnumSet.of(StaffLimitOverrideStatus.PENDING,
            StaffLimitOverrideStatus.APPROVED);

    private final StaffGradeChangeRepository repository;
    private final StaffGradeLimitChangeRepository limitRepository;
    private final StaffMemberRepository memberRepository;
    private final StaffMemberChangeRepository memberChangeRepository;
    private final StaffRegisterRowRepository rowRepository;
    private final StaffLimitOverrideRepository overrideRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /** Grade changes, newest first: the checker's queue ({@code status=PENDING}) and each grade's history. */
    @Transactional(readOnly = true)
    public List<StaffGradeChangeResponse> changes(String grade, StaffGradeChangeStatus status) {
        String wanted = StringUtils.isBlank(grade) ? null : StaffGrades.normalise(grade);
        return repository.findAllByOrderByIdDesc().stream()
                .filter(change -> wanted == null || wanted.equals(change.getGrade())
                        || wanted.equals(change.getNewGrade()))
                .filter(change -> status == null || status == change.getStatus())
                .map(this::response)
                .toList();
    }

    /**
     * Proposes retiring or renaming a grade, for a second person to approve.
     *
     * @throws ValidationException a RENAME without a new name, a RETIRE with one, a name too long, or a new name the same
     *                             as the old
     * @throws NotFoundException   the grade is not in the matrix
     * @throws ConflictException   something stands in the way: see {@link StaffGradeChangeService}
     */
    @Transactional
    public StaffGradeChangeResponse propose(ProposeStaffGradeChangeRequest request) {
        if (!StaffGrades.valid(request.getGrade())) {
            throw new ValidationException(StaffGrades.MESSAGE);
        }
        String grade = StaffGrades.normalise(request.getGrade());
        String newGrade = null;
        if (request.getAction() == StaffGradeChangeAction.RENAME) {
            if (StringUtils.isBlank(request.getNewGrade())) {
                throw new ValidationException("The new name is required to rename a grade");
            }
            if (!StaffGrades.valid(request.getNewGrade())) {
                throw new ValidationException(StaffGrades.MESSAGE);
            }
            newGrade = StaffGrades.normalise(request.getNewGrade());
            if (newGrade.equals(grade)) {
                throw new ValidationException(String.format("Grade %s is already called that; give it a different"
                        + " name", grade));
            }
        } else if (StringUtils.isNotBlank(request.getNewGrade())) {
            throw new ValidationException("A grade being retired takes no new name; propose a RENAME to rename it");
        }
        StaffGradeChange change = StaffGradeChange.builder()
                .action(request.getAction())
                .grade(grade)
                .newGrade(newGrade)
                .status(StaffGradeChangeStatus.PENDING)
                .proposedBy(authService.getLoggedInUsername())
                .proposedAt(marketTimeZone.nowUtc())
                .proposalComment(StringUtils.trimToNull(request.getComment()))
                .build();
        requireCanCarryOut(change);
        try {
            change = repository.saveAndFlush(change);
        } catch (DataIntegrityViolationException ex) {
            // Someone else proposed a change naming the grade, or the new name, at the same moment.
            throw new ConflictException(String.format("A change to grade %s is already waiting for approval; approve,"
                    + " reject or withdraw it first", newGrade != null ? grade + " or " + newGrade : grade));
        }
        log.info("Grade change {} proposed by {}: {}", change.getId(), change.getProposedBy(), describe(change));
        audit(PROPOSED, change, change.getProposedBy(), describe(change));
        return response(change);
    }

    /**
     * Approves or rejects a proposed grade change. Never by whoever proposed it. An approval re-checks everything the
     * proposal was checked for, under the register's lock, and then carries the change out.
     *
     * @throws NotFoundException     no such change, or the grade has left the matrix while it waited
     * @throws ConflictException     it is no longer pending, or something now stands in the way
     * @throws AccessDeniedException the caller proposed it
     * @throws ValidationException   a rejection without a reason
     */
    @Transactional
    public StaffGradeChangeResponse decide(Long id, StaffGradeLimitDecisionRequest request) {
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        StaffGradeChange change = pendingForUpdate(id);
        String username = authService.getLoggedInUsername();
        if (StringUtils.equalsIgnoreCase(username, change.getProposedBy())) {
            throw new AccessDeniedException(String.format("%s proposed grade change %d and cannot also approve or"
                    + " reject it; another credit manager or SUPER_ADMIN must", username, id));
        }
        String comment = StringUtils.trimToNull(request.getComment());
        LocalDateTime now = marketTimeZone.nowUtc();
        if (request.getDecision() == StaffGradeLimitDecision.REJECTED) {
            if (comment == null) {
                throw new ValidationException("A reason is required to reject a grade change");
            }
            settle(change, StaffGradeChangeStatus.REJECTED, username, now, comment);
            log.info("Grade change {} rejected by {}: {}", id, username, describe(change));
            audit(REJECTED, change, username, describe(change) + ";reason:" + comment);
            return response(change);
        }
        requireCanCarryOut(change);
        List<StaffGradeLimitChange> limits = limitRepository.findByGradeAndStatusOrderByEffectiveFrom(
                change.getGrade(), StaffGradeLimitChangeStatus.APPROVED);
        if (change.getAction() == StaffGradeChangeAction.RENAME) {
            rename(change, limits, username, now, comment);
        } else {
            retire(limits, change, now);
            settle(change, StaffGradeChangeStatus.APPROVED, username, now, comment);
            log.info("Grade {} retired by change {}, approved by {}: {} limit(s) retired", change.getGrade(), id,
                    username, limits.size());
            audit(RETIRED, change, username, describe(change) + ";limitsRetired:" + limits.size());
        }
        return response(change);
    }

    /**
     * Takes back a proposal before anyone decides it. Only whoever proposed it may.
     *
     * @throws NotFoundException     no such change
     * @throws ConflictException     it is no longer pending
     * @throws AccessDeniedException the caller did not propose it
     */
    @Transactional
    public StaffGradeChangeResponse withdraw(Long id) {
        StaffGradeChange change = pendingForUpdate(id);
        String username = authService.getLoggedInUsername();
        if (!StringUtils.equals(username, change.getProposedBy())) {
            throw new AccessDeniedException(String.format("Only %s, who proposed grade change %d, can withdraw it;"
                    + " anyone else approves or rejects it", change.getProposedBy(), id));
        }
        settle(change, StaffGradeChangeStatus.WITHDRAWN, username, marketTimeZone.nowUtc(), null);
        log.info("Grade change {} withdrawn by {}: {}", id, username, describe(change));
        audit(WITHDRAWN, change, username, describe(change));
        return response(change);
    }

    /**
     * Carries a rename out: the limits copied to the new name and the old ones retired, then the staff and their
     * overrides moved. A member's updatedAt is left alone, because the offer run reads it as Human Capital having
     * dealt with a payroll reconciliation's flag, and a rename deals with nothing.
     */
    private void rename(StaffGradeChange change, List<StaffGradeLimitChange> limits, String approver,
                        LocalDateTime now, String comment) {
        String from = change.getGrade();
        String to = change.getNewGrade();
        List<StaffGradeLimitChange> copies = new ArrayList<>();
        for (StaffGradeLimitChange limit : limits) {
            copies.add(StaffGradeLimitChange.builder()
                    .grade(to)
                    .scoreBand(limit.getScoreBand())
                    .maximumLimit(limit.getMaximumLimit())
                    .effectiveFrom(limit.getEffectiveFrom())
                    .status(StaffGradeLimitChangeStatus.APPROVED)
                    .proposedBy(change.getProposedBy())
                    .proposedAt(change.getProposedAt())
                    .proposalComment(String.format("Renamed from %s by grade change %d (limit change %d)", from,
                            change.getId(), limit.getId()))
                    .decidedBy(approver)
                    .decidedAt(now)
                    .decisionComment(comment)
                    .build());
        }
        retire(limits, change, now);
        limitRepository.saveAllAndFlush(copies);

        List<StaffMember> members = memberRepository.findByGradeForUpdate(from);
        List<StaffMemberChange> history = new ArrayList<>();
        for (StaffMember member : members) {
            member.setGrade(to);
            history.add(StaffMemberChange.builder()
                    .staffMemberId(member.getId())
                    .gradeChangeId(change.getId())
                    .field(StaffFields.GRADE)
                    .previousValue(from)
                    .newValue(to)
                    .submittedBy(change.getProposedBy())
                    .approvedBy(approver)
                    .changedAt(now)
                    .build());
        }
        memberRepository.saveAll(members);
        memberChangeRepository.saveAll(history);

        List<StaffLimitOverride> overrides = overrideRepository.findByGradeForUpdate(from, OVERRIDES_MOVED);
        overrides.forEach(override -> override.setGrade(to));
        overrideRepository.saveAll(overrides);

        change.setStaffMembersMoved(members.size());
        change.setLimitOverridesMoved(overrides.size());
        settle(change, StaffGradeChangeStatus.APPROVED, approver, now, comment);
        log.info("Grade {} renamed {} by change {}, approved by {}: {} limit(s), {} staff member(s) and {} limit"
                        + " override(s) moved", from, to, change.getId(), approver, copies.size(), members.size(),
                overrides.size());
        audit(RENAMED, change, approver, describe(change) + ";limitsMoved:" + copies.size() + ";staffMembersMoved:"
                + members.size() + ";limitOverridesMoved:" + overrides.size());
    }

    private void retire(List<StaffGradeLimitChange> limits, StaffGradeChange change, LocalDateTime now) {
        for (StaffGradeLimitChange limit : limits) {
            limit.setStatus(StaffGradeLimitChangeStatus.RETIRED);
            limit.setRetiredBy(change.getId());
            limit.setRetiredAt(now);
        }
        // Out of the approved index before a copy under the same name could go into it.
        limitRepository.saveAllAndFlush(limits);
    }

    /**
     * What a proposal is checked for, and an approval again: the grade is in the matrix; no limit change, grade change
     * or register batch waiting for approval would land on it (or on the new name); a retirement leaves nobody employed
     * without a grade; and a new name is not a grade already.
     */
    private void requireCanCarryOut(StaffGradeChange change) {
        String grade = change.getGrade();
        if (!limitRepository.existsByGradeAndStatus(grade, StaffGradeLimitChangeStatus.APPROVED)) {
            throw new NotFoundException("Grade " + grade + " is not in the grade-to-limit matrix");
        }
        requireNothingWaitingOn(grade, change);
        rowRepository.findPendingBatchesStaging(grade).stream().findFirst().ifPresent(batchId -> {
            throw new ConflictException(String.format("Staff register batch %d, waiting for approval, puts staff at"
                    + " grade %s; approve, reject or withdraw it first", batchId, grade));
        });
        if (change.getAction() == StaffGradeChangeAction.RETIRE) {
            long employed = memberRepository.countByGradeAndEmploymentStatusIn(grade, EMPLOYED);
            if (employed > 0) {
                throw new ConflictException(String.format("%d staff member%s still employed hold%s grade %s; move"
                                + " them to another grade through the staff register first, or rename the grade",
                        employed, employed == 1 ? "" : "s", employed == 1 ? "s" : "", grade));
            }
            return;
        }
        String newGrade = change.getNewGrade();
        if (limitRepository.existsByGradeAndStatus(newGrade, StaffGradeLimitChangeStatus.APPROVED)) {
            throw new ConflictException(String.format("Grade %s is already in the grade-to-limit matrix; to merge %s"
                    + " into it, move the staff through the staff register and then retire %s", newGrade, grade,
                    grade));
        }
        requireNothingWaitingOn(newGrade, change);
    }

    private void requireNothingWaitingOn(String grade, StaffGradeChange change) {
        limitRepository.findByGradeAndStatusOrderByEffectiveFrom(grade, StaffGradeLimitChangeStatus.PENDING).stream()
                .findFirst().ifPresent(waiting -> {
                    throw new ConflictException(String.format("A limit change to grade %s is waiting for approval"
                            + " (change %d); approve, reject or withdraw it first", grade, waiting.getId()));
                });
        repository.findNaming(grade, StaffGradeChangeStatus.PENDING).stream()
                .filter(other -> !other.getId().equals(change.getId()))
                .findFirst().ifPresent(waiting -> {
                    throw new ConflictException(String.format("Grade change %d, naming grade %s, is waiting for"
                            + " approval; approve, reject or withdraw it first", waiting.getId(), grade));
                });
    }

    private StaffGradeChange pendingForUpdate(Long id) {
        StaffGradeChange change = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Grade change " + id + " not found"));
        if (change.getStatus() != StaffGradeChangeStatus.PENDING) {
            throw new ConflictException(String.format("Grade change %d is already %s", id,
                    change.getStatus().name().toLowerCase()));
        }
        return change;
    }

    private void settle(StaffGradeChange change, StaffGradeChangeStatus status, String username, LocalDateTime at,
                        String comment) {
        change.setStatus(status);
        change.setDecidedBy(username);
        change.setDecidedAt(at);
        change.setDecisionComment(comment);
        repository.save(change);
    }

    private StaffGradeChangeResponse response(StaffGradeChange change) {
        return StaffGradeChangeResponse.of(change, change.getStatus() == StaffGradeChangeStatus.PENDING
                ? memberRepository.countByGrade(change.getGrade()) : null);
    }

    private static String describe(StaffGradeChange change) {
        return "action:" + change.getAction() + ";grade:" + change.getGrade()
                + (change.getNewGrade() == null ? "" : ";newGrade:" + change.getNewGrade());
    }

    private void audit(String eventType, StaffGradeChange change, String actor, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(eventType)
                .entityType(ENTITY).entityId(String.valueOf(change.getId()))
                .actorId(actor).channelUsed(CHANNEL)
                .detail(detail));
    }
}
