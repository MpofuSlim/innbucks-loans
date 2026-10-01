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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Staff Grocery Loans as the bank's staff see them: Credit, Finance and Human Capital read them, with the agreement
 * each was accepted under, and Credit can stop one before it is paid out. A borrower who stops being ACTIVE before
 * their loan is paid out has it cancelled at once: the register is the product's credit control (FR-SGL-007).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffLoanService {

    static final String CANCELLED = "STAFF_LOAN_CANCELLED";

    private final StaffLoanRepository loanRepository;
    private final StaffLoanAgreementRepository agreementRepository;
    private final StaffLoanPolicy policy;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /** Loans, newest first, optionally of one status and one employee number. */
    @Transactional(readOnly = true)
    public Page<StaffLoanResponse> loans(StaffLoanStatus status, String employeeNumber, Pageable pageable) {
        Specification<StaffLoan> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
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
     * up; the borrower may apply again.
     *
     * @throws NotFoundException no such loan
     * @throws ConflictException it has been paid out, or is already closed
     */
    @Transactional
    public StaffLoanResponse cancel(Long id, String reason) {
        StaffLoan loan = loanRepository.lockById(id).orElseThrow(() -> notFound(id));
        if (loan.getStatus() != StaffLoanStatus.AWAITING_DISBURSEMENT) {
            throw new ConflictException("Staff loan " + loan.getReference() + " is " + loan.getStatus()
                    + "; only a loan awaiting disbursement can be cancelled");
        }
        String by = authService.getLoggedInUsername();
        cancel(loan, by, reason.strip(), "admin-portal");
        return StaffLoanResponse.of(loan, marketTimeZone.today(), policy.arrearsGraceDays());
    }

    /**
     * A borrower who is no longer ACTIVE is not paid out (FR-SGL-005, FR-SGL-007): their loan awaiting disbursement is
     * cancelled in the register approval's own transaction. A loan already paid out is left to the exit process.
     */
    @EventListener
    public void onEmploymentStatusChanged(StaffEmploymentStatusChanged change) {
        if (change.to() == StaffEmploymentStatus.ACTIVE) {
            return;
        }
        loanRepository.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(change.staffMemberId(),
                        List.of(StaffLoanStatus.AWAITING_DISBURSEMENT))
                .flatMap(loan -> loanRepository.lockById(loan.getId()))
                .filter(loan -> loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT)
                .ifPresent(loan -> cancel(loan, change.approvedBy(), "Employment status changed to " + change.to()
                        + " before the loan was paid out (register batch " + change.batchId() + ")", "system"));
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
