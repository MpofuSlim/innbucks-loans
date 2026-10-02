package zw.co.innbucks.loans.core.staff.loan;

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
import zw.co.innbucks.loans.core.staff.offer.StaffArrearsOverride;
import zw.co.innbucks.loans.core.staff.offer.StaffArrearsOverrideRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffArrearsOverrideStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Credit's arrears overrides (FR-SGL-014): "no Staff Grocery Loan where the employee has an outstanding written-off or
 * delinquent balance on any InnBucks facility, unless overridden by Credit with reason recorded."
 *
 * <p>What it overrides is a written-off balance. An overdue loan is still open, and FR-SGL-013 allows nobody a second
 * loan beside an open one, so no override lifts it: it has to be repaid first. Loans knows only its own Staff Grocery
 * Loans; written-off and delinquent balances on other InnBucks facilities are the core banking system's to report.</p>
 *
 * <p>A CREDIT_MANAGER or SUPER_ADMIN proposes one for a member who owes a written-off loan, with a reason and a last
 * day at most {@value #MAX_VALIDITY_DAYS} days ahead; another approves or rejects it, never the proposer (the database
 * enforces it too), and only the proposer may withdraw it. Approving replaces the member's earlier approved override.
 * While it is in force the member is offered, may apply and may accept like anyone else; the loan they accept names
 * it and uses it up, so each override answers for one loan. Credit may revoke an unused one. Decisions take the
 * register's lock, so none lands in the middle of an offer run or an acceptance. Every step is audited.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffArrearsOverrideService {

    static final String PROPOSED = "STAFF_ARREARS_OVERRIDE_PROPOSED";
    static final String APPROVED = "STAFF_ARREARS_OVERRIDE_APPROVED";
    static final String REJECTED = "STAFF_ARREARS_OVERRIDE_REJECTED";
    static final String WITHDRAWN = "STAFF_ARREARS_OVERRIDE_WITHDRAWN";
    static final String SUPERSEDED = "STAFF_ARREARS_OVERRIDE_SUPERSEDED";
    static final String REVOKED = "STAFF_ARREARS_OVERRIDE_REVOKED";
    static final String USED = "STAFF_ARREARS_OVERRIDE_USED";
    /** The furthest ahead an override's last day may be. */
    static final int MAX_VALIDITY_DAYS = 90;
    private static final String ENTITY = "STAFF_ARREARS_OVERRIDE";

    private final StaffArrearsOverrideRepository repository;
    private final StaffMemberRepository memberRepository;
    private final StaffLoanRepository loanRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * Proposes letting one member borrow despite a written-off balance, up to {@code validUntil}.
     *
     * @throws NotFoundException   no such employee
     * @throws ValidationException the last day is before today or more than {@value #MAX_VALIDITY_DAYS} days ahead
     * @throws ConflictException   they owe no written-off loan, or already have an override waiting for a decision
     */
    @Transactional
    public StaffArrearsOverrideResponse propose(ProposeStaffArrearsOverrideRequest request) {
        StaffMember member = memberOrThrow(request.getEmployeeNumber());
        LocalDate today = marketTimeZone.today();
        LocalDate validUntil = request.getValidUntil();
        if (validUntil.isBefore(today)) {
            throw new ValidationException("Valid until must be today (" + today + ") or later");
        }
        if (validUntil.isAfter(today.plusDays(MAX_VALIDITY_DAYS))) {
            throw new ValidationException(String.format("Valid until must be at most %d days ahead, on or before %s",
                    MAX_VALIDITY_DAYS, today.plusDays(MAX_VALIDITY_DAYS)));
        }
        requireWrittenOff(member, null);
        repository.findByStaffMemberIdAndStatus(member.getId(), StaffArrearsOverrideStatus.PENDING)
                .ifPresent(pending -> {
                    throw pendingExists(member, pending.getId());
                });
        String username = authService.getLoggedInUsername();
        StaffArrearsOverride override;
        try {
            override = repository.saveAndFlush(StaffArrearsOverride.builder()
                    .staffMemberId(member.getId())
                    .reason(request.getReason().strip())
                    .validUntil(validUntil)
                    .status(StaffArrearsOverrideStatus.PENDING)
                    .proposedBy(username)
                    .proposedAt(marketTimeZone.nowUtc())
                    .build());
        } catch (DataIntegrityViolationException race) {
            throw pendingExists(member, null);
        }
        log.info("Arrears override {} proposed by {}: {}", override.getId(), username, describe(override, member));
        audit(PROPOSED, override, username, "admin-portal",
                describe(override, member) + ";reason:" + override.getReason());
        return StaffArrearsOverrideResponse.of(override, member, null, today);
    }

    /**
     * Approves or rejects an override; never by whoever proposed it. Approval replaces the member's earlier approved
     * override.
     *
     * @throws NotFoundException     no such override
     * @throws ConflictException     it is no longer pending, its last day has passed, or the member no longer owes a
     *                               written-off loan
     * @throws AccessDeniedException the caller proposed it
     * @throws ValidationException   a rejection without a reason
     */
    @Transactional
    public StaffArrearsOverrideResponse decide(Long id, StaffArrearsOverrideDecisionRequest request) {
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        StaffArrearsOverride override = pendingForUpdate(id);
        StaffMember member = memberRepository.findById(override.getStaffMemberId()).orElseThrow();
        String username = authService.getLoggedInUsername();
        if (StringUtils.equalsIgnoreCase(username, override.getProposedBy())) {
            throw new AccessDeniedException(String.format("%s proposed arrears override %d and cannot also approve"
                    + " or reject it; another credit manager or SUPER_ADMIN must", username, id));
        }
        String comment = StringUtils.trimToNull(request.getComment());
        LocalDateTime now = marketTimeZone.nowUtc();
        LocalDate today = marketTimeZone.localDay(now);
        if (request.getDecision() == StaffArrearsOverrideDecision.REJECTED) {
            if (comment == null) {
                throw new ValidationException("A reason is required to reject an arrears override");
            }
            settle(override, StaffArrearsOverrideStatus.REJECTED, username, now, comment);
            log.info("Arrears override {} rejected by {}: {}", id, username, describe(override, member));
            audit(REJECTED, override, username, "admin-portal", describe(override, member) + ";reason:" + comment);
            return StaffArrearsOverrideResponse.of(override, member, null, today);
        }
        if (today.isAfter(override.getValidUntil())) {
            throw new ConflictException(String.format("Arrears override %d's last day, %s, has passed; reject it and"
                    + " propose a new one", id, override.getValidUntil()));
        }
        requireWrittenOff(member, id);
        repository.findForUpdate(member.getId(), StaffArrearsOverrideStatus.APPROVED).ifPresent(earlier -> {
            earlier.setStatus(StaffArrearsOverrideStatus.SUPERSEDED);
            earlier.setSupersededBy(override.getId());
            earlier.setSupersededAt(now);
            // Out of the approved index before this one goes into it.
            repository.saveAndFlush(earlier);
            log.info("Arrears override {} superseded by {}", earlier.getId(), id);
            audit(SUPERSEDED, earlier, username, "admin-portal", describe(earlier, member) + ";supersededBy:" + id);
        });
        settle(override, StaffArrearsOverrideStatus.APPROVED, username, now, comment);
        log.info("Arrears override {} approved by {}: {}", id, username, describe(override, member));
        audit(APPROVED, override, username, "admin-portal", describe(override, member));
        return StaffArrearsOverrideResponse.of(override, member, null, today);
    }

    /**
     * Takes back a pending override. Only whoever proposed it may.
     *
     * @throws NotFoundException     no such override
     * @throws ConflictException     it is no longer pending
     * @throws AccessDeniedException the caller did not propose it
     */
    @Transactional
    public StaffArrearsOverrideResponse withdraw(Long id) {
        StaffArrearsOverride override = pendingForUpdate(id);
        String username = authService.getLoggedInUsername();
        if (!StringUtils.equals(username, override.getProposedBy())) {
            throw new AccessDeniedException(String.format("Only %s, who proposed arrears override %d, can withdraw"
                    + " it; anyone else approves or rejects it", override.getProposedBy(), id));
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        settle(override, StaffArrearsOverrideStatus.WITHDRAWN, username, now, null);
        StaffMember member = memberRepository.findById(override.getStaffMemberId()).orElseThrow();
        log.info("Arrears override {} withdrawn by {}", id, username);
        audit(WITHDRAWN, override, username, "admin-portal", describe(override, member));
        return StaffArrearsOverrideResponse.of(override, member, null, marketTimeZone.localDay(now));
    }

    /**
     * Ends an approved override before it is used, so the written-off balance stops new loans again. An offer the
     * member still holds stays open, but it cannot be accepted, and the next weekly run withdraws it.
     *
     * @throws NotFoundException no such override
     * @throws ConflictException it is not APPROVED: never in force, or already used
     */
    @Transactional
    public StaffArrearsOverrideResponse revoke(Long id, RevokeStaffArrearsOverrideRequest request) {
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        StaffArrearsOverride override = repository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (override.getStatus() != StaffArrearsOverrideStatus.APPROVED) {
            throw new ConflictException(String.format("Arrears override %d is %s, so there is nothing to revoke", id,
                    override.getStatus().name().toLowerCase(Locale.ROOT)));
        }
        String username = authService.getLoggedInUsername();
        LocalDateTime now = marketTimeZone.nowUtc();
        override.setStatus(StaffArrearsOverrideStatus.REVOKED);
        override.setRevokedBy(username);
        override.setRevokedAt(now);
        override.setRevocationReason(request.getReason().strip());
        repository.save(override);
        StaffMember member = memberRepository.findById(override.getStaffMemberId()).orElseThrow();
        log.info("Arrears override {} revoked by {}", id, username);
        audit(REVOKED, override, username, "admin-portal",
                describe(override, member) + ";reason:" + override.getRevocationReason());
        return StaffArrearsOverrideResponse.of(override, member, null, marketTimeZone.localDay(now));
    }

    /** Overrides, newest first; {@code status=PENDING} is the checker's queue. */
    @Transactional(readOnly = true)
    public Page<StaffArrearsOverrideResponse> overrides(StaffArrearsOverrideStatus status, String employeeNumber,
                                                        Pageable pageable) {
        Specification<StaffArrearsOverride> filter = (root, query, cb) -> null;
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (StringUtils.isNotBlank(employeeNumber)) {
            Long memberId = memberRepository.findByEmployeeNumber(normalise(employeeNumber)).map(StaffMember::getId)
                    .orElse(-1L);
            filter = filter.and((root, query, cb) -> cb.equal(root.get("staffMemberId"), memberId));
        }
        Page<StaffArrearsOverride> page = repository.findAll(filter, PageRequest.of(pageable.getPageNumber(),
                pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id")));
        List<Long> memberIds = page.getContent().stream().map(StaffArrearsOverride::getStaffMemberId).distinct()
                .toList();
        Map<Long, StaffMember> members = memberIds.isEmpty() ? Map.of()
                : memberRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(StaffMember::getId, Function.identity()));
        List<Long> loanIds = page.getContent().stream().map(StaffArrearsOverride::getStaffLoanId)
                .filter(Objects::nonNull).toList();
        Map<Long, String> references = loanIds.isEmpty() ? Map.of()
                : loanRepository.findAllById(loanIds).stream()
                .collect(Collectors.toMap(StaffLoan::getId, StaffLoan::getReference));
        LocalDate today = marketTimeZone.today();
        return page.map(override -> StaffArrearsOverrideResponse.of(override, members.get(override.getStaffMemberId()),
                references.get(override.getStaffLoanId()), today));
    }

    /**
     * Uses {@code override} up on {@code loan}, the loan the member accepted under it. In the acceptance's transaction,
     * which holds the register's lock, so the override is still in force.
     */
    void use(StaffArrearsOverride override, StaffLoan loan, String borrower) {
        StaffArrearsOverride held = repository.findByIdForUpdate(override.getId()).orElseThrow();
        LocalDateTime now = marketTimeZone.nowUtc();
        if (!held.inForceOn(marketTimeZone.localDay(now))) {
            throw new IllegalStateException("Arrears override " + held.getId() + " is not in force: " + held);
        }
        held.setStatus(StaffArrearsOverrideStatus.USED);
        held.setStaffLoanId(loan.getId());
        held.setUsedAt(now);
        repository.save(held);
        log.info("Arrears override {} used for Staff Grocery Loan {}", held.getId(), loan.getReference());
        audit(USED, held, borrower, "superapp", "employee:" + loan.getEmployeeNumber() + ";loan:"
                + loan.getReference());
    }

    /** @param overrideId the override being decided, for the message; null when proposing */
    private void requireWrittenOff(StaffMember member, Long overrideId) {
        if (loanRepository.existsByStaffMemberIdAndStatus(member.getId(), StaffLoanStatus.WRITTEN_OFF)) {
            return;
        }
        String subject = overrideId == null ? "there is nothing to override"
                : "arrears override " + overrideId + " is not needed; reject it";
        throw new ConflictException(String.format("Employee %s owes no written-off Staff Grocery Loan, so %s. An"
                + " overdue loan cannot be overridden: it must be repaid first", member.getEmployeeNumber(), subject));
    }

    private StaffArrearsOverride pendingForUpdate(Long id) {
        StaffArrearsOverride override = repository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (override.getStatus() != StaffArrearsOverrideStatus.PENDING) {
            throw new ConflictException(String.format("Arrears override %d is already %s", id,
                    override.getStatus().name().toLowerCase(Locale.ROOT)));
        }
        return override;
    }

    private void settle(StaffArrearsOverride override, StaffArrearsOverrideStatus status, String username,
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
        return new ConflictException(String.format("Employee %s already has an arrears override waiting for a"
                        + " decision%s; approve, reject or withdraw it first", member.getEmployeeNumber(),
                pendingId == null ? "" : " (" + pendingId + ")"));
    }

    private static NotFoundException notFound(Long id) {
        return new NotFoundException("Arrears override " + id + " not found");
    }

    private static String describe(StaffArrearsOverride override, StaffMember member) {
        return "employee:" + member.getEmployeeNumber() + ";validUntil:" + override.getValidUntil();
    }

    private void audit(String eventType, StaffArrearsOverride override, String actor, String channel, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(eventType)
                .entityType(ENTITY).entityId(String.valueOf(override.getId()))
                .actorId(actor).channelUsed(channel)
                .detail(detail));
    }
}
