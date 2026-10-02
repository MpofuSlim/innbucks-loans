package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.criteria.Predicate;
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
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writing off a Staff Grocery Loan, and reversing a write-off (FR-GEN-011): "write-off, reversal and adjustment with
 * restricted entitlements, maker-checker and full audit trail".
 *
 * <p>A CREDIT_MANAGER, FINANCE or SUPER_ADMIN user proposes one for a loan, with a reason; FINANCE or a SUPER_ADMIN
 * approves or rejects it, never the proposer (the database enforces it too), and only the proposer may withdraw it.
 * One request may wait per loan. Approval re-checks the loan, then applies the change in the same transaction.</p>
 *
 * <ul>
 *   <li>A write-off is for a paid-out (DISBURSED) loan whose voucher can no longer be spent: writing off a debt whose
 *   value can still be drawn would let the borrower keep spending it. A loan awaiting disbursement is cancelled, not
 *   written off. A written-off loan is still owed and still recovered; it also stops the borrower taking another
 *   Staff Grocery Loan unless Credit overrides (FR-SGL-014).</li>
 *   <li>A reversal puts a loan written off in error back to DISBURSED. It is refused while the borrower holds another
 *   open loan, such as one taken under Credit's arrears override: nobody holds two (FR-SGL-013).</li>
 * </ul>
 *
 * <p>The amount is what the loan owed when proposed, as loans knows it: the core banking system holds the balance
 * once a loan is paid out, so it is a record of the decision, not a posting. Decisions take the register's lock, so
 * none lands in the middle of an offer run or an acceptance. Every step is audited, the loan's change of state too.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffLoanWriteOffService {

    static final String PROPOSED = "PROPOSED";
    static final String APPROVED = "APPROVED";
    static final String REJECTED = "REJECTED";
    static final String WITHDRAWN = "WITHDRAWN";
    /** The loan's own audit events, on the STAFF_LOAN entity. */
    static final String LOAN_WRITTEN_OFF = "STAFF_LOAN_WRITTEN_OFF";
    static final String LOAN_REINSTATED = "STAFF_LOAN_WRITE_OFF_REVERSED";
    private static final String ENTITY = "STAFF_LOAN_WRITE_OFF";

    private final StaffLoanWriteOffRepository repository;
    private final StaffLoanRepository loanRepository;
    private final StaffMemberRepository memberRepository;
    private final VoucherRepository voucherRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * Proposes writing off a loan, or reversing its write-off.
     *
     * @throws NotFoundException no such loan
     * @throws ConflictException the loan is not in a state this kind applies to, its voucher can still be spent, the
     *                           borrower holds another open loan, or a request already waits for this loan
     */
    @Transactional
    public StaffLoanWriteOffResponse propose(ProposeStaffLoanWriteOffRequest request) {
        StaffLoan loan = loanRepository.findById(request.getStaffLoanId())
                .orElseThrow(() -> new NotFoundException("Staff loan " + request.getStaffLoanId() + " not found"));
        requireApplicable(request.getKind(), loan, marketTimeZone.nowUtc());
        repository.findByStaffLoanIdAndStatus(loan.getId(), StaffLoanWriteOffStatus.PENDING).ifPresent(pending -> {
            throw pendingExists(loan, pending.getId());
        });
        String username = authService.getLoggedInUsername();
        StaffLoanWriteOff proposal;
        try {
            proposal = repository.saveAndFlush(StaffLoanWriteOff.builder()
                    .staffLoanId(loan.getId())
                    .kind(request.getKind())
                    .amount(loan.outstanding())
                    .currency(loan.getCurrency())
                    .reason(request.getReason().strip())
                    .status(StaffLoanWriteOffStatus.PENDING)
                    .proposedBy(username)
                    .proposedAt(marketTimeZone.nowUtc())
                    .build());
        } catch (DataIntegrityViolationException race) {
            throw pendingExists(loan, null);
        }
        log.info("{} {} of {} proposed by {}", request.getKind(), proposal.getId(), loan.getReference(), username);
        audit(proposal, PROPOSED, username, describe(proposal, loan) + ";reason:" + proposal.getReason());
        return StaffLoanWriteOffResponse.of(proposal, loan);
    }

    /**
     * Approves or rejects a request; never by whoever proposed it. Approval checks the loan again and applies the
     * change: DISBURSED becomes WRITTEN_OFF, or back.
     *
     * @throws NotFoundException     no such request
     * @throws ConflictException     it is no longer pending, or the loan no longer allows it
     * @throws AccessDeniedException the caller proposed it
     * @throws ValidationException   a rejection without a reason
     */
    @Transactional
    public StaffLoanWriteOffResponse decide(Long id, StaffLoanWriteOffDecisionRequest request) {
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        StaffLoanWriteOff pending = pendingForUpdate(id);
        StaffLoan loan = loanRepository.lockById(pending.getStaffLoanId()).orElseThrow();
        String username = authService.getLoggedInUsername();
        if (StringUtils.equalsIgnoreCase(username, pending.getProposedBy())) {
            throw new AccessDeniedException(String.format("%s proposed write-off request %d and cannot also approve"
                    + " or reject it; another FINANCE user or a SUPER_ADMIN must", username, id));
        }
        String comment = StringUtils.trimToNull(request.getComment());
        LocalDateTime now = marketTimeZone.nowUtc();
        if (request.getDecision() == StaffLoanWriteOffDecision.REJECTED) {
            if (comment == null) {
                throw new ValidationException("A reason is required to reject a write-off request");
            }
            settle(pending, StaffLoanWriteOffStatus.REJECTED, username, now, comment);
            log.info("{} {} of {} rejected by {}", pending.getKind(), id, loan.getReference(), username);
            audit(pending, REJECTED, username, describe(pending, loan) + ";reason:" + comment);
            return StaffLoanWriteOffResponse.of(pending, loan);
        }
        requireApplicable(pending.getKind(), loan, now);
        StaffLoanStatus from = loan.getStatus();
        if (pending.getKind() == StaffLoanWriteOffKind.WRITE_OFF) {
            loan.writeOff(now);
        } else {
            loan.reinstate();
        }
        loanRepository.save(loan);
        settle(pending, StaffLoanWriteOffStatus.APPROVED, username, now, comment);
        log.info("{} {} of {} approved by {}: {} -> {}", pending.getKind(), id, loan.getReference(), username, from,
                loan.getStatus());
        audit(pending, APPROVED, username, describe(pending, loan));
        auditService.record(AuditLog.builder()
                .eventType(pending.getKind() == StaffLoanWriteOffKind.WRITE_OFF ? LOAN_WRITTEN_OFF : LOAN_REINSTATED)
                .entityType("STAFF_LOAN").entityId(String.valueOf(loan.getId()))
                .actorId(username).channelUsed("admin-portal")
                .stateTransitionDelta("{\"from\":\"" + from + "\",\"to\":\"" + loan.getStatus() + "\"}")
                .detail("reference:" + loan.getReference() + ";writeOffRequest:" + id + ";proposedBy:"
                        + pending.getProposedBy() + ";amount:" + pending.getAmount() + " " + pending.getCurrency()));
        return StaffLoanWriteOffResponse.of(pending, loan);
    }

    /**
     * Takes back a pending request. Only whoever proposed it may.
     *
     * @throws NotFoundException     no such request
     * @throws ConflictException     it is no longer pending
     * @throws AccessDeniedException the caller did not propose it
     */
    @Transactional
    public StaffLoanWriteOffResponse withdraw(Long id) {
        StaffLoanWriteOff pending = pendingForUpdate(id);
        String username = authService.getLoggedInUsername();
        if (!StringUtils.equals(username, pending.getProposedBy())) {
            throw new AccessDeniedException(String.format("Only %s, who proposed write-off request %d, can withdraw"
                    + " it; anyone else entitled approves or rejects it", pending.getProposedBy(), id));
        }
        settle(pending, StaffLoanWriteOffStatus.WITHDRAWN, username, marketTimeZone.nowUtc(), null);
        StaffLoan loan = loanRepository.findById(pending.getStaffLoanId()).orElseThrow();
        log.info("{} {} of {} withdrawn by {}", pending.getKind(), id, loan.getReference(), username);
        audit(pending, WITHDRAWN, username, describe(pending, loan));
        return StaffLoanWriteOffResponse.of(pending, loan);
    }

    /** Requests, newest first; {@code status=PENDING} is the checker's queue. */
    @Transactional(readOnly = true)
    public Page<StaffLoanWriteOffResponse> requests(StaffLoanWriteOffStatus status, StaffLoanWriteOffKind kind,
                                                    Long staffLoanId, Pageable pageable) {
        Specification<StaffLoanWriteOff> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (kind != null) {
                predicates.add(cb.equal(root.get("kind"), kind));
            }
            if (staffLoanId != null) {
                predicates.add(cb.equal(root.get("staffLoanId"), staffLoanId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<StaffLoanWriteOff> page = repository.findAll(filter, PageRequest.of(pageable.getPageNumber(),
                pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id")));
        List<Long> loanIds = page.getContent().stream().map(StaffLoanWriteOff::getStaffLoanId).distinct().toList();
        Map<Long, StaffLoan> loans = loanIds.isEmpty() ? Map.of() : loanRepository.findAllById(loanIds).stream()
                .collect(Collectors.toMap(StaffLoan::getId, Function.identity()));
        return page.map(request -> StaffLoanWriteOffResponse.of(request, loans.get(request.getStaffLoanId())));
    }

    /** Refuses {@code kind} for {@code loan} as it stands at {@code now}, with what to do instead. */
    private void requireApplicable(StaffLoanWriteOffKind kind, StaffLoan loan, LocalDateTime now) {
        if (kind == StaffLoanWriteOffKind.WRITE_OFF) {
            if (loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT) {
                throw new ConflictException("Staff loan " + loan.getReference() + " has not been paid out: cancel it"
                        + " instead of writing it off");
            }
            if (loan.getStatus() != StaffLoanStatus.DISBURSED) {
                throw new ConflictException("Staff loan " + loan.getReference() + " is " + loan.getStatus()
                        + "; only a paid-out loan (DISBURSED) can be written off");
            }
            voucherRepository.findFirstByStaffMemberIdAndLoanAccountOrderByIdDesc(loan.getStaffMemberId(),
                            loan.getReference())
                    .filter(voucher -> voucher.statusAt(now).isOpen())
                    .ifPresent(voucher -> {
                        throw voucherOpen(loan, voucher, now);
                    });
            return;
        }
        if (loan.getStatus() != StaffLoanStatus.WRITTEN_OFF) {
            throw new ConflictException("Staff loan " + loan.getReference() + " is " + loan.getStatus()
                    + ", not written off, so there is no write-off to reverse");
        }
        loanRepository.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(loan.getStaffMemberId(), StaffLoanStatus.OPEN)
                .ifPresent(open -> {
                    throw new ConflictException(String.format("Employee %s holds staff loan %s, which is open;"
                                    + " putting %s back would give them two open loans. It can be reversed once %s"
                                    + " is closed", loan.getEmployeeNumber(), open.getReference(), loan.getReference(),
                            open.getReference()));
                });
    }

    private ConflictException voucherOpen(StaffLoan loan, Voucher voucher, LocalDateTime now) {
        String how = voucher.statusAt(now) == VoucherStatus.ISSUED ? "cancel the voucher or wait until it expires"
                : "wait until it expires";
        return new ConflictException(String.format("Staff loan %s's voucher %d can still be spent, until %s; a loan"
                        + " whose value can still be drawn cannot be written off: %s", loan.getReference(),
                voucher.getId(), marketTimeZone.render(voucher.getExpiresAt()), how));
    }

    private StaffLoanWriteOff pendingForUpdate(Long id) {
        StaffLoanWriteOff request = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Write-off request " + id + " not found"));
        if (request.getStatus() != StaffLoanWriteOffStatus.PENDING) {
            throw new ConflictException(String.format("Write-off request %d is already %s", id,
                    request.getStatus().name().toLowerCase(Locale.ROOT)));
        }
        return request;
    }

    private void settle(StaffLoanWriteOff request, StaffLoanWriteOffStatus status, String username, LocalDateTime at,
                        String comment) {
        request.setStatus(status);
        request.setDecidedBy(username);
        request.setDecidedAt(at);
        request.setDecisionComment(comment);
        repository.save(request);
    }

    private static ConflictException pendingExists(StaffLoan loan, Long pendingId) {
        return new ConflictException(String.format("Staff loan %s already has a write-off request waiting for a"
                        + " decision%s; approve, reject or withdraw it first", loan.getReference(),
                pendingId == null ? "" : " (" + pendingId + ")"));
    }

    private static String describe(StaffLoanWriteOff request, StaffLoan loan) {
        return "loan:" + loan.getReference() + ";employee:" + loan.getEmployeeNumber() + ";amount:"
                + request.getAmount() + " " + request.getCurrency();
    }

    private void audit(StaffLoanWriteOff request, String action, String actor, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(request.getKind().event(action))
                .entityType(ENTITY).entityId(String.valueOf(request.getId()))
                .actorId(actor).channelUsed("admin-portal")
                .detail(detail));
    }
}
