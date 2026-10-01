package zw.co.innbucks.loans.core.staff.offer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Credit's limit overrides for one staff member (FR-SGL-011): the only way an offer is made at anything but the
 * member's grade limit. A CREDIT_MANAGER or SUPER_ADMIN proposes one with a reason; another approves or rejects it,
 * never the proposer (the database enforces it too), and only the proposer may withdraw it. Approving replaces the
 * member's earlier override. Credit may revoke an approved one, so the grade limit applies again. Every step is audited.
 *
 * <p>An override is set for the grade the member holds when it is proposed and applies only while they still hold
 * it, so a regrading brings back the matrix limit rather than carrying a judgement about another grade. It takes
 * effect at the next weekly offer, like any limit change, except that approving a limit of 0 also withdraws the
 * member's open offer at once. Decisions take the register's lock, so none lands in the middle of an offer run.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffLimitOverrideService {

    static final String PROPOSED = "STAFF_LIMIT_OVERRIDE_PROPOSED";
    static final String APPROVED = "STAFF_LIMIT_OVERRIDE_APPROVED";
    static final String REJECTED = "STAFF_LIMIT_OVERRIDE_REJECTED";
    static final String WITHDRAWN = "STAFF_LIMIT_OVERRIDE_WITHDRAWN";
    static final String SUPERSEDED = "STAFF_LIMIT_OVERRIDE_SUPERSEDED";
    static final String REVOKED = "STAFF_LIMIT_OVERRIDE_REVOKED";
    private static final String ENTITY = "STAFF_LIMIT_OVERRIDE";

    private final StaffLimitOverrideRepository repository;
    private final StaffMemberRepository memberRepository;
    private final StaffOfferRepository offerRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * Proposes a limit for one member, set for the grade they hold now.
     *
     * @throws NotFoundException no such employee
     * @throws ConflictException they already have an override waiting for a decision
     */
    @Transactional
    public StaffLimitOverrideResponse propose(ProposeStaffLimitOverrideRequest request) {
        StaffMember member = memberOrThrow(request.getEmployeeNumber());
        repository.findByStaffMemberIdAndStatus(member.getId(), StaffLimitOverrideStatus.PENDING)
                .ifPresent(pending -> {
                    throw pendingExists(member, pending.getId());
                });
        String username = authService.getLoggedInUsername();
        StaffLimitOverride override;
        try {
            override = repository.saveAndFlush(StaffLimitOverride.builder()
                    .staffMemberId(member.getId())
                    .grade(member.getGrade())
                    // The request allows at most two decimals, so this only fixes the scale: 150 is stored as 150.00.
                    .amount(request.getAmount().setScale(2, RoundingMode.UNNECESSARY))
                    .reason(request.getReason().strip())
                    .status(StaffLimitOverrideStatus.PENDING)
                    .proposedBy(username)
                    .proposedAt(marketTimeZone.nowUtc())
                    .build());
        } catch (DataIntegrityViolationException race) {
            throw pendingExists(member, null);
        }
        log.info("Limit override {} proposed by {}: {}", override.getId(), username, describe(override, member));
        audit(PROPOSED, override, username, describe(override, member) + ";reason:" + override.getReason());
        return StaffLimitOverrideResponse.of(override, member);
    }

    /**
     * Approves or rejects an override; never by whoever proposed it. Approval replaces the member's earlier override,
     * and a limit of 0 withdraws their open offer.
     *
     * @throws NotFoundException     no such override
     * @throws ConflictException     it is no longer pending, or the member's grade changed since it was proposed
     * @throws AccessDeniedException the caller proposed it
     * @throws ValidationException   a rejection without a reason
     */
    @Transactional
    public StaffLimitOverrideResponse decide(Long id, StaffLimitOverrideDecisionRequest request) {
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        StaffLimitOverride override = pendingForUpdate(id);
        StaffMember member = memberRepository.findById(override.getStaffMemberId()).orElseThrow();
        String username = authService.getLoggedInUsername();
        if (StringUtils.equalsIgnoreCase(username, override.getProposedBy())) {
            throw new AccessDeniedException(String.format("%s proposed limit override %d and cannot also approve or"
                    + " reject it; another credit manager or SUPER_ADMIN must", username, id));
        }
        String comment = StringUtils.trimToNull(request.getComment());
        LocalDateTime now = marketTimeZone.nowUtc();
        if (request.getDecision() == StaffLimitOverrideDecision.REJECTED) {
            if (comment == null) {
                throw new ValidationException("A reason is required to reject a limit override");
            }
            settle(override, StaffLimitOverrideStatus.REJECTED, username, now, comment);
            log.info("Limit override {} rejected by {}: {}", id, username, describe(override, member));
            audit(REJECTED, override, username, describe(override, member) + ";reason:" + comment);
            return StaffLimitOverrideResponse.of(override, member);
        }
        if (!override.getGrade().equals(member.getGrade())) {
            throw new ConflictException(String.format("Employee %s's grade has changed from %s to %s since limit"
                            + " override %d was proposed; reject it and propose one for the new grade",
                    member.getEmployeeNumber(), override.getGrade(), member.getGrade(), id));
        }
        repository.findForUpdate(member.getId(), StaffLimitOverrideStatus.APPROVED).ifPresent(earlier -> {
            earlier.setStatus(StaffLimitOverrideStatus.SUPERSEDED);
            earlier.setSupersededBy(override.getId());
            earlier.setSupersededAt(now);
            // Out of the approved index before this one goes into it.
            repository.saveAndFlush(earlier);
            log.info("Limit override {} superseded by {}", earlier.getId(), id);
            audit(SUPERSEDED, earlier, username, describe(earlier, member) + ";supersededBy:" + id);
        });
        settle(override, StaffLimitOverrideStatus.APPROVED, username, now, comment);
        String withdrawn = "";
        if (override.blocks()) {
            withdrawn = offerRepository.findByStaffMemberIdAndStatus(member.getId(), StaffOfferStatus.ACTIVE)
                    .map(offer -> {
                        offer.close(StaffOfferStatus.WITHDRAWN, now, "Credit set their limit to 0 (limit override "
                                + id + ")");
                        offerRepository.save(offer);
                        return ";withdrewOffer:" + offer.getId();
                    })
                    .orElse("");
        }
        log.info("Limit override {} approved by {}: {}{}", id, username, describe(override, member), withdrawn);
        audit(APPROVED, override, username, describe(override, member) + withdrawn);
        return StaffLimitOverrideResponse.of(override, member);
    }

    /**
     * Takes back a pending override. Only whoever proposed it may.
     *
     * @throws NotFoundException     no such override
     * @throws ConflictException     it is no longer pending
     * @throws AccessDeniedException the caller did not propose it
     */
    @Transactional
    public StaffLimitOverrideResponse withdraw(Long id) {
        StaffLimitOverride override = pendingForUpdate(id);
        String username = authService.getLoggedInUsername();
        if (!StringUtils.equals(username, override.getProposedBy())) {
            throw new AccessDeniedException(String.format("Only %s, who proposed limit override %d, can withdraw it;"
                    + " anyone else approves or rejects it", override.getProposedBy(), id));
        }
        settle(override, StaffLimitOverrideStatus.WITHDRAWN, username, marketTimeZone.nowUtc(), null);
        StaffMember member = memberRepository.findById(override.getStaffMemberId()).orElseThrow();
        log.info("Limit override {} withdrawn by {}", id, username);
        audit(WITHDRAWN, override, username, describe(override, member));
        return StaffLimitOverrideResponse.of(override, member);
    }

    /**
     * Ends an approved override, so the member's grade limit applies from their next offer.
     *
     * @throws NotFoundException no such override
     * @throws ConflictException it is not in force (not APPROVED)
     */
    @Transactional
    public StaffLimitOverrideResponse revoke(Long id, RevokeStaffLimitOverrideRequest request) {
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        StaffLimitOverride override = repository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (override.getStatus() != StaffLimitOverrideStatus.APPROVED) {
            throw new ConflictException(String.format("Limit override %d is %s, so there is nothing to revoke", id,
                    override.getStatus().name().toLowerCase(Locale.ROOT)));
        }
        String username = authService.getLoggedInUsername();
        override.setStatus(StaffLimitOverrideStatus.REVOKED);
        override.setRevokedBy(username);
        override.setRevokedAt(marketTimeZone.nowUtc());
        override.setRevocationReason(request.getReason().strip());
        repository.save(override);
        StaffMember member = memberRepository.findById(override.getStaffMemberId()).orElseThrow();
        log.info("Limit override {} revoked by {}", id, username);
        audit(REVOKED, override, username, describe(override, member) + ";reason:" + override.getRevocationReason());
        return StaffLimitOverrideResponse.of(override, member);
    }

    /** Overrides, newest first; {@code status=PENDING} is the checker's queue. */
    @Transactional(readOnly = true)
    public Page<StaffLimitOverrideResponse> overrides(StaffLimitOverrideStatus status, String employeeNumber,
                                                      Pageable pageable) {
        Specification<StaffLimitOverride> filter = (root, query, cb) -> null;
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (StringUtils.isNotBlank(employeeNumber)) {
            Long memberId = memberRepository.findByEmployeeNumber(normalise(employeeNumber)).map(StaffMember::getId)
                    .orElse(-1L);
            filter = filter.and((root, query, cb) -> cb.equal(root.get("staffMemberId"), memberId));
        }
        Page<StaffLimitOverride> page = repository.findAll(filter, PageRequest.of(pageable.getPageNumber(),
                pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id")));
        List<Long> memberIds = page.getContent().stream().map(StaffLimitOverride::getStaffMemberId).distinct()
                .toList();
        Map<Long, StaffMember> members = memberIds.isEmpty() ? Map.of()
                : memberRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(StaffMember::getId, Function.identity()));
        return page.map(override -> StaffLimitOverrideResponse.of(override, members.get(override.getStaffMemberId())));
    }

    private StaffLimitOverride pendingForUpdate(Long id) {
        StaffLimitOverride override = repository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (override.getStatus() != StaffLimitOverrideStatus.PENDING) {
            throw new ConflictException(String.format("Limit override %d is already %s", id,
                    override.getStatus().name().toLowerCase(Locale.ROOT)));
        }
        return override;
    }

    private void settle(StaffLimitOverride override, StaffLimitOverrideStatus status, String username,
                        LocalDateTime at, String comment) {
        override.setStatus(status);
        override.setDecidedBy(username);
        override.setDecidedAt(at);
        override.setDecisionComment(comment);
        repository.save(override);
    }

    private StaffMember memberOrThrow(String employeeNumber) {
        String wanted = normalise(employeeNumber);
        return memberRepository.findByEmployeeNumber(wanted)
                .orElseThrow(() -> new NotFoundException("Employee " + wanted + " is not on the staff register"));
    }

    private static String normalise(String employeeNumber) {
        return StringUtils.upperCase(StringUtils.strip(employeeNumber), Locale.ROOT);
    }

    private static ConflictException pendingExists(StaffMember member, Long pendingId) {
        return new ConflictException(String.format("Employee %s already has a limit override waiting for a decision%s;"
                        + " approve, reject or withdraw it first", member.getEmployeeNumber(),
                pendingId == null ? "" : " (" + pendingId + ")"));
    }

    private static NotFoundException notFound(Long id) {
        return new NotFoundException("Limit override " + id + " not found");
    }

    private static String describe(StaffLimitOverride override, StaffMember member) {
        return "employee:" + member.getEmployeeNumber() + ";grade:" + override.getGrade() + ";amount:"
                + override.getAmount().toPlainString();
    }

    private void audit(String eventType, StaffLimitOverride override, String actor, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(eventType)
                .entityType(ENTITY).entityId(String.valueOf(override.getId()))
                .actorId(actor).channelUsed("admin-portal")
                .detail(detail));
    }
}
