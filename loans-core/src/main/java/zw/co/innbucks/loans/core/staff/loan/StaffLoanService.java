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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatusChanged;
import zw.co.innbucks.loans.core.voucher.GetMoreDispatcher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Staff Grocery Loans as the bank's staff see them: Credit, Finance and Human Capital read them, with the agreement
 * each was accepted under, and Credit can stop one before it is paid out. A borrower who stops being ACTIVE before
 * their loan is paid out has it cancelled at once: the register is the product's credit control (FR-SGL-007). Once it
 * is paid out it is flagged instead, and the people who must act are told (BRD 3.8).
 *
 * <p>A loan awaiting its GetMore voucher may already have had a coupon made for it at GetMore: cancelling it sends its
 * issuance to CANCELLING, and once the cancellation commits whatever it left live at GetMore is withdrawn
 * ({@link StaffLoanVoucherIssuer#cleanup}). An attempt calling GetMore right now is never cut off: Credit's cancel
 * waits for it (a 409 to repeat), and an employment change leaves it to find the loan cancelled when it settles,
 * which withdraws the coupon it made. Cancelling a loan paid out as its GetMore voucher is
 * {@link StaffLoanCancellation}'s.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffLoanService {

    static final String CANCELLED = "STAFF_LOAN_CANCELLED";
    static final String EMPLOYMENT_FLAGGED = "STAFF_LOAN_EMPLOYMENT_FLAGGED";
    static final String EMPLOYMENT_FLAG_CLEARED = "STAFF_LOAN_EMPLOYMENT_FLAG_CLEARED";

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
    private final StaffLoanVoucherIssuanceRepository issuanceRepository;
    private final GetMoreDispatcher getMoreDispatcher;
    private final GetMoreProperties getMoreProperties;

    /**
     * Loans, newest first, optionally of one status, one employee number, and only those flagged (true) or not (false)
     * for a borrower no longer ACTIVE.
     */
    @Transactional(readOnly = true)
    public Page<StaffLoanResponse> loans(StaffLoanStatus status, String employeeNumber, Boolean flagged,
                                         Pageable pageable) {
        Specification<StaffLoan> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
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
     * Stops a loan before it is paid out: an acceptance in error, or one that should not be paid. Its offer stays taken
     * up; the borrower may apply again. A GetMore coupon its voucher issuance may have made is withdrawn once this
     * commits, in the background.
     *
     * @throws NotFoundException           no such loan
     * @throws ConflictException           it has been paid out, or is already closed
     * @throws IssuanceInProgressException its GetMore voucher is being issued right now
     */
    @Transactional
    public StaffLoanResponse cancel(Long id, String reason) {
        StaffLoan loan = loanRepository.lockById(id).orElseThrow(() -> notFound(id));
        if (loan.getStatus() != StaffLoanStatus.AWAITING_DISBURSEMENT) {
            throw new ConflictException("Staff loan " + loan.getReference() + " is " + loan.getStatus()
                    + "; only a loan awaiting disbursement can be cancelled");
        }
        String by = authService.getLoggedInUsername();
        cancelAwaiting(loan, by, reason.strip(), "admin-portal", true)
                .ifPresent(this::cleanUpAfterCommit);
        return StaffLoanResponse.of(loan, marketTimeZone.today(), policy.arrearsGraceDays());
    }

    /**
     * Cancels a loan awaiting its voucher, locked by the caller, in the caller's transaction, audited; its GetMore
     * voucher issuance, if it has one still open, goes to CANCELLING. An attempt claimed less than the stale-claim time
     * ago may be calling GetMore right now: with {@code refuseWhileCalling} nothing is cancelled
     * ({@link IssuanceInProgressException}); without, the loan is cancelled and the issuance left to that attempt,
     * which finds the loan cancelled when it settles and withdraws what it made.
     *
     * @return the issuance now CANCELLING, whose coupons the caller has withdrawn once this commits
     * @throws IssuanceInProgressException {@code refuseWhileCalling}, and an attempt is calling GetMore right now
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> cancelAwaiting(StaffLoan loan, String by, String reason, String channel,
                                         boolean refuseWhileCalling) {
        LocalDateTime now = marketTimeZone.nowUtc().truncatedTo(ChronoUnit.MICROS);
        StaffLoanVoucherIssuance issuance = issuanceRepository.lockByStaffLoanId(loan.getId())
                .filter(open -> StaffLoanVoucherIssuanceStatus.OPEN.contains(open.getStatus()))
                .orElse(null);
        boolean calling = issuance != null
                && issuance.isClaimFresh(now, getMoreProperties.getIssuance().getStaleClaim());
        if (calling && refuseWhileCalling) {
            throw new IssuanceInProgressException(loan.getReference());
        }
        cancelLocked(loan, by, reason, channel);
        if (issuance == null || calling) {
            if (calling) {
                log.info("Staff loan {} cancelled while its GetMore voucher is being issued; that attempt withdraws"
                        + " what it made", loan.getReference());
            }
            return Optional.empty();
        }
        if (issuance.getStatus() != StaffLoanVoucherIssuanceStatus.CANCELLING) {
            issuance.cancelling(now);
            issuanceRepository.save(issuance);
        }
        return Optional.of(issuance.getId());
    }

    /**
     * Cancels a loan locked by the caller, in the caller's transaction, and audits it. The caller checks it may be:
     * awaiting disbursement, or paid out as a GetMore voucher nothing was ever spent from, now withdrawn.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelLocked(StaffLoan loan, String by, String reason, String channel) {
        cancel(loan, by, reason, channel);
    }

    /** Withdraws what the issuance left at GetMore once the current transaction commits, in the background. */
    private void cleanUpAfterCommit(Long issuanceId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            getMoreDispatcher.cleanup(issuanceId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                getMoreDispatcher.cleanup(issuanceId);
            }
        });
    }

    /**
     * A borrower who is no longer ACTIVE is not paid out (FR-SGL-005, FR-SGL-007): their loan awaiting disbursement is
     * cancelled in the register approval's own transaction, whatever its GetMore voucher issuance is doing; once the
     * approval commits, what that issuance left at GetMore is withdrawn in the background (one calling GetMore right
     * now withdraws its own coupon when it settles). A loan already paid out is flagged with the status they moved to,
     * and the flag cleared when they are ACTIVE again (BRD 3.8); each change is audited and, once the approval
     * commits, emailed to whoever it concerns. Its GetMore coupon stays live (Q5).
     */
    @EventListener
    public void onEmploymentStatusChanged(StaffEmploymentStatusChanged change) {
        if (change.to() != StaffEmploymentStatus.ACTIVE) {
            loanRepository.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(change.staffMemberId(),
                            List.of(StaffLoanStatus.AWAITING_DISBURSEMENT))
                    .flatMap(loan -> loanRepository.lockById(loan.getId()))
                    .filter(loan -> loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT)
                    .flatMap(loan -> cancelAwaiting(loan, change.approvedBy(), "Employment status changed to "
                            + change.to() + " before the loan was paid out (register batch " + change.batchId() + ")",
                            "system", false))
                    .ifPresent(this::cleanUpAfterCommit);
        }
        for (StaffLoan found : loanRepository.findByStaffMemberIdInAndStatusIn(List.of(change.staffMemberId()),
                PAID_OUT)) {
            loanRepository.lockById(found.getId())
                    .filter(loan -> PAID_OUT.contains(loan.getStatus()))
                    .ifPresent(loan -> flagEmployment(loan, change));
        }
    }

    private void flagEmployment(StaffLoan loan, StaffEmploymentStatusChanged change) {
        StaffEmploymentStatus from = loan.getEmploymentFlag();
        StaffEmploymentStatus to = change.to() == StaffEmploymentStatus.ACTIVE ? null : change.to();
        if (from == to) {
            return;
        }
        loan.flagEmployment(change.to(), marketTimeZone.nowUtc(), change.batchId());
        loanRepository.save(loan);
        auditService.record(AuditLog.builder()
                .eventType(to == null ? EMPLOYMENT_FLAG_CLEARED : EMPLOYMENT_FLAGGED)
                .entityType("STAFF_LOAN").entityId(String.valueOf(loan.getId()))
                .actorId(change.approvedBy()).channelUsed("system")
                .detail("reference:" + loan.getReference() + ";from:" + (from == null ? "ACTIVE" : from) + ";to:"
                        + change.to() + ";action:" + (to == null ? "none" : EmploymentFlagAction.of(to))
                        + ";batch:" + change.batchId()));
        log.info("Staff loan {} of {}: borrower {} (was {}), register batch {}", loan.getReference(),
                loan.getEmployeeNumber(), change.to(), from == null ? "ACTIVE" : from, change.batchId());
        flagNotifier.notifyAfterCommit(new StaffLoanEmploymentFlagNotifier.Change(loan.getReference(),
                loan.getEmployeeNumber(), loan.outstanding(), loan.getCurrency(), loan.getDueDate(), from, to,
                loan.isSimulatedGetMore()));
    }

    private void cancel(StaffLoan loan, String by, String reason, String channel) {
        loan.cancel(marketTimeZone.nowUtc(), by, reason);
        loanRepository.save(loan);
        auditService.record(AuditLog.builder()
                .eventType(CANCELLED)
                .entityType("STAFF_LOAN").entityId(String.valueOf(loan.getId()))
                .actorId(by).channelUsed(channel)
                .detail("reference:" + loan.getReference() + ";reason:" + reason));
        log.info("Staff loan {} of {} cancelled by {}: {}", loan.getReference(), loan.getEmployeeNumber(), by, reason);
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
