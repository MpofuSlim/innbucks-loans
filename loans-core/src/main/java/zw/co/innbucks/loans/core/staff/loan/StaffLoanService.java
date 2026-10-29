package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatusChanged;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationService;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Staff Grocery Loans as the bank's staff see them: Credit, Finance and Human Capital read them, with the agreement
 * each was accepted under, and Credit can stop one before it is paid out. A borrower who stops being ACTIVE before
 * their loan is paid out has it cancelled at once: the register is the product's credit control (FR-SGL-007). Once it
 * is paid out it is flagged instead, and the people who must act are told (BRD 3.8).
 *
 * <p>A cash loan whose payout may already be on its way (SENDING, BOOKED or IN_DOUBT) is neither cancelled nor
 * flagged: the bank may have paid it. Its payout settles it, and if it is paid to a borrower no longer ACTIVE, the flag
 * is applied then ({@link #flagEmploymentAtPayout}). A cash loan that is cancelled tells its borrower so.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffLoanService {

    static final String CANCELLED = "STAFF_LOAN_CANCELLED";
    static final String EMPLOYMENT_FLAGGED = "STAFF_LOAN_EMPLOYMENT_FLAGGED";
    static final String EMPLOYMENT_FLAG_CLEARED = "STAFF_LOAN_EMPLOYMENT_FLAG_CLEARED";
    static final String PAYOUT_LEAVER_IN_FLIGHT = "STAFF_LOAN_PAYOUT_LEAVER_IN_FLIGHT";

    /** Paid out and not settled: what a borrower who stops being ACTIVE still owes. */
    private static final List<StaffLoanStatus> PAID_OUT = List.of(StaffLoanStatus.DISBURSED,
            StaffLoanStatus.WRITTEN_OFF);

    private final StaffLoanRepository loanRepository;
    private final StaffLoanAgreementRepository agreementRepository;
    private final StaffLoanPolicy policy;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;
    private final StaffLoanEmploymentFlagNotifier flagNotifier;
    private final StaffNotificationService notificationService;

    /**
     * Loans, newest first, optionally of one status, one employee number, only those flagged (true) or not (false)
     * for a borrower no longer ACTIVE, one payout status (cash loans only), and one way of paying out.
     */
    @Transactional(readOnly = true)
    public Page<StaffLoanResponse> loans(StaffLoanStatus status, String employeeNumber, Boolean flagged,
                                         StaffLoanPayoutStatus payoutStatus, StaffLoanPayoutMethod payoutMethod,
                                         Pageable pageable) {
        Specification<StaffLoan> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (payoutStatus != null) {
                predicates.add(cb.equal(root.get("payoutStatus"), payoutStatus));
            }
            if (payoutMethod != null) {
                predicates.add(cb.equal(root.get("payoutMethod"), payoutMethod));
            }
            if (flagged != null) {
                predicates.add(flagged ? cb.isNotNull(root.get("employmentFlag"))
                        : cb.isNull(root.get("employmentFlag")));
            }
            if (StringUtils.isNotBlank(employeeNumber)) {
                predicates.add(cb.equal(cb.upper(root.get("employeeNumber")), employeeNumber.strip().toUpperCase()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        LocalDate today = marketTimeZone.today();
        return loanRepository.findAll(filter, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "id")))
                .map(loan -> StaffLoanResponse.of(loan, today, policy.arrearsGraceDays()));
    }

    /**
     * A loan with the agreement it was accepted under, its seal checked.
     *
     * @throws NotFoundException no such loan
     */
    @Transactional(readOnly = true)
    public StaffLoanDetailResponse loan(Long id) {
        StaffLoan loan = loanRepository.findById(id).orElseThrow(() -> notFound(id));
        StaffLoanAgreementResponse agreement = agreementRepository.findByStaffLoanId(id)
                .map(StaffLoanService::agreement).orElse(null);
        return new StaffLoanDetailResponse(
                StaffLoanResponse.of(loan, marketTimeZone.today(), policy.arrearsGraceDays()), agreement);
    }

    /**
     * A loan as the list shows it, read as it stands now: what an action on its payout answers with, once that action
     * has committed. Read in a transaction of its own, as it names its merchant.
     *
     * @throws NotFoundException no such loan
     */
    @Transactional(readOnly = true)
    public StaffLoanResponse summary(Long id) {
        StaffLoan loan = loanRepository.findById(id).orElseThrow(() -> notFound(id));
        return StaffLoanResponse.of(loan, marketTimeZone.today(), policy.arrearsGraceDays());
    }

    /**
     * Stops a loan before it is paid out: an acceptance in error, or one that should not be paid. Its offer stays taken
     * up; the borrower may apply again. A cash loan can be stopped only while nothing was booked for it (its payout
     * READY, or REFUSED by the bank); its payout is WITHDRAWN, and its borrower told it was not paid out.
     *
     * @throws NotFoundException no such loan
     * @throws ConflictException it has been paid out, is already closed, or is a cash loan the bank may already have
     *                           paid (its payout SENDING, BOOKED or IN_DOUBT)
     */
    @Transactional
    public StaffLoanResponse cancel(Long id, String reason) {
        StaffLoan loan = loanRepository.lockById(id).orElseThrow(() -> notFound(id));
        if (loan.getStatus() != StaffLoanStatus.AWAITING_DISBURSEMENT) {
            throw new ConflictException("Staff loan " + loan.getReference() + " is " + loan.getStatus()
                    + "; only a loan awaiting disbursement can be cancelled");
        }
        if (loan.payoutInFlight()) {
            throw new ConflictException("Staff loan " + loan.getReference() + " is being paid out (payout "
                    + loan.getPayoutStatus() + "): the bank may already have paid it, so it can no longer be"
                    + " cancelled. Its status check settles it; if the bank confirms it was never booked, a"
                    + " SUPER_ADMIN records that with POST /lending/v1/staff-loans/" + loan.getId()
                    + "/payout/not-booked");
        }
        String by = authService.getLoggedInUsername();
        cancelLocked(loan, by, reason.strip(), "admin-portal");
        return StaffLoanResponse.of(loan, marketTimeZone.today(), policy.arrearsGraceDays());
    }

    /**
     * A borrower who is no longer ACTIVE is not paid out (FR-SGL-005, FR-SGL-007): their loan awaiting disbursement is
     * cancelled in the register approval's own transaction. A loan already paid out is flagged with the status they
     * moved to, and the flag cleared when they are ACTIVE again (BRD 3.8); each change is audited and, once the
     * approval commits, emailed to whoever it concerns.
     *
     * <p>Each loan is looked up by id and first read under its row lock, so what is decided is its state now, not as
     * it was before a payout settled it. A cash loan whose payout may already be on its way is neither cancelled nor
     * flagged (the money may or may not be out): it is logged and audited, and flagged if it is paid out after all.
     */
    @EventListener
    public void onEmploymentStatusChanged(StaffEmploymentStatusChanged change) {
        if (change.to() != StaffEmploymentStatus.ACTIVE) {
            for (Long id : loanRepository.findIdsByStaffMemberIdAndStatusIn(change.staffMemberId(),
                    List.of(StaffLoanStatus.AWAITING_DISBURSEMENT))) {
                loanRepository.lockById(id)
                        .filter(loan -> loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT)
                        .ifPresent(loan -> {
                            if (loan.payoutInFlight()) {
                                leaverInFlight(loan, change);
                            } else {
                                cancelLocked(loan, change.approvedBy(), "Employment status changed to " + change.to()
                                        + " before the loan was paid out (register batch " + change.batchId() + ")",
                                        "system");
                            }
                        });
            }
        }
        for (Long id : loanRepository.findIdsByStaffMemberIdAndStatusIn(change.staffMemberId(), PAID_OUT)) {
            loanRepository.lockById(id)
                    .filter(loan -> PAID_OUT.contains(loan.getStatus()))
                    .ifPresent(loan -> flagEmployment(loan, change.to(), change.approvedBy(), change.batchId()));
        }
    }

    /**
     * A cash loan paid out to a borrower who is no longer ACTIVE: flagged as if their move had come after it, so Human
     * Capital, Payroll or Credit are told. Called under the loan's row lock, in the transaction that settles the payout.
     *
     * @param batchId the register batch that last changed the borrower
     */
    void flagEmploymentAtPayout(StaffLoan loan, StaffEmploymentStatus status, String actor, Long batchId) {
        if (!PAID_OUT.contains(loan.getStatus())) {
            throw new IllegalStateException("Staff loan " + loan.getReference() + " is " + loan.getStatus()
                    + "; only a loan paid out is flagged");
        }
        flagEmployment(loan, status, actor, batchId);
    }

    private void leaverInFlight(StaffLoan loan, StaffEmploymentStatusChanged change) {
        log.warn("Staff loan {} of {}: borrower {} (register batch {}) while its payout is {}; the bank may already have"
                        + " paid it, so it is neither cancelled nor flagged until the payout settles (audited)",
                loan.getReference(), loan.getEmployeeNumber(), change.to(), change.batchId(), loan.getPayoutStatus());
        auditService.record(AuditLog.builder()
                .eventType(PAYOUT_LEAVER_IN_FLIGHT)
                .entityType("STAFF_LOAN").entityId(String.valueOf(loan.getId()))
                .actorId(change.approvedBy()).channelUsed("system")
                .detail("reference:" + loan.getReference() + ";payout:" + loan.getPayoutStatus() + ";to:"
                        + change.to() + ";batch:" + change.batchId()));
    }

    private void flagEmployment(StaffLoan loan, StaffEmploymentStatus status, String actor, Long batchId) {
        StaffEmploymentStatus from = loan.getEmploymentFlag();
        StaffEmploymentStatus to = status == StaffEmploymentStatus.ACTIVE ? null : status;
        if (from == to) {
            return;
        }
        loan.flagEmployment(status, marketTimeZone.nowUtc(), batchId);
        loanRepository.save(loan);
        auditService.record(AuditLog.builder()
                .eventType(to == null ? EMPLOYMENT_FLAG_CLEARED : EMPLOYMENT_FLAGGED)
                .entityType("STAFF_LOAN").entityId(String.valueOf(loan.getId()))
                .actorId(actor).channelUsed("system")
                .detail("reference:" + loan.getReference() + ";from:" + (from == null ? "ACTIVE" : from) + ";to:"
                        + status + ";action:" + (to == null ? "none" : EmploymentFlagAction.of(to))
                        + ";batch:" + batchId));
        log.info("Staff loan {} of {}: borrower {} (was {}), register batch {}", loan.getReference(),
                loan.getEmployeeNumber(), status, from == null ? "ACTIVE" : from, batchId);
        flagNotifier.notifyAfterCommit(new StaffLoanEmploymentFlagNotifier.Change(loan.getReference(),
                loan.getEmployeeNumber(), loan.outstanding(), loan.getCurrency(), loan.getDueDate(), from, to));
    }

    /**
     * Cancels a loan the caller holds the row lock of and has checked is AWAITING_DISBURSEMENT, and for a cash loan,
     * that nothing was booked (its payout READY or REFUSED; it becomes WITHDRAWN). A cash loan's borrower is told it was
     * not paid out, once this transaction commits.
     */
    void cancelLocked(StaffLoan loan, String by, String reason, String channel) {
        loan.cancel(marketTimeZone.nowUtc(), by, reason);
        loanRepository.save(loan);
        auditService.record(AuditLog.builder()
                .eventType(CANCELLED)
                .entityType("STAFF_LOAN").entityId(String.valueOf(loan.getId()))
                .actorId(by).channelUsed(channel)
                .detail("reference:" + loan.getReference() + ";reason:" + reason));
        log.info("Staff loan {} of {} cancelled by {}: {}", loan.getReference(), loan.getEmployeeNumber(), by, reason);
        if (loan.getPayoutMethod() == StaffLoanPayoutMethod.CASH) {
            notificationService.notifyLoan(loan, StaffNotificationTemplate.LOAN_NOT_PAID_OUT,
                    StaffNotificationTemplate.LOAN_NOT_PAID_OUT.notPaidOutText(loan.getReference()));
        }
    }

    private static StaffLoanAgreementResponse agreement(StaffLoanAgreement agreement) {
        boolean intact = AuditService.sha256Hex(agreement.getContent()).equals(agreement.getContentSha256())
                && StaffLoanJourneyService.evidenceSha256(agreement).equals(agreement.getEvidenceSha256());
        if (!intact) {
            log.error("The agreement of staff loan {} no longer matches its seal", agreement.getStaffLoanId());
        }
        return new StaffLoanAgreementResponse(agreement.getInstrumentType(), agreement.getTemplateVersion(),
                agreement.getTitle(), agreement.getContent(), agreement.getContentSha256(), agreement.getAcceptedBy(),
                agreement.getAcceptedAt(), agreement.getDeviceId(), agreement.getIpAddress(),
                agreement.getForwardedFor(), agreement.getUserAgent(), agreement.getAuthenticationMethod(),
                agreement.getAssertionId(), agreement.getEvidenceSha256(), intact);
    }

    private static NotFoundException notFound(Long id) {
        return new NotFoundException("Staff loan " + id + " not found");
    }
}
