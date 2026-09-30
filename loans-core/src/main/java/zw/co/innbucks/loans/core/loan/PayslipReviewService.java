package zw.co.innbucks.loans.core.loan;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.document.DocumentOrigin;
import zw.co.innbucks.loans.core.document.LoanDocumentRepository;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.LoanApprovalException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.workflow.SystemStage;
import zw.co.innbucks.loans.core.workflow.WorkAssignmentGuard;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The payslip review queue (FR-SSB-007). An application whose payslip raises a concern is held back from
 * SSB lodgement until a credit officer decides: CLEARED releases it to be lodged as usual; CONFIRMED
 * upholds the suspicion and rejects it, as a credit rejection kept in the decision log. Nothing about the
 * hold is shown to the originator or the customer, who would otherwise be told a fraud check is running.
 */
@Slf4j
@Service
public class PayslipReviewService {

    static final String PAYSLIP_REVIEW_REQUIRED = "PAYSLIP_REVIEW_REQUIRED";
    static final String PAYSLIP_REVIEW_CLEARED = "PAYSLIP_REVIEW_CLEARED";
    static final String PAYSLIP_REVIEW_CONFIRMED = "PAYSLIP_REVIEW_CONFIRMED";
    /** The credit reason a confirmed suspicion rejects the application with. */
    static final String CONFIRMED_REASON_CODE = "REJECT_SUSPECTED_FRAUD";

    private final LoanRepository loanRepository;
    private final PayslipFraudFlagRepository payslipFraudFlagRepository;
    private final CreditDecisionLog creditDecisionLog;
    private final AuthService authService;
    private final LoanMapper loanMapper;
    private final LoanNotificationService loanNotificationService;
    private final AuditService auditService;
    private final DeductionCancellationService deductionCancellationService;
    private final LoanDocumentRepository loanDocumentRepository;
    private final TransactionTemplate transactionTemplate;
    private final WorkAssignmentGuard workAssignmentGuard;

    public PayslipReviewService(LoanRepository loanRepository, PayslipFraudFlagRepository payslipFraudFlagRepository,
                                CreditDecisionLog creditDecisionLog, AuthService authService, LoanMapper loanMapper,
                                LoanNotificationService loanNotificationService, AuditService auditService,
                                DeductionCancellationService deductionCancellationService,
                                LoanDocumentRepository loanDocumentRepository,
                                PlatformTransactionManager transactionManager,
                                WorkAssignmentGuard workAssignmentGuard) {
        this.loanRepository = loanRepository;
        this.payslipFraudFlagRepository = payslipFraudFlagRepository;
        this.creditDecisionLog = creditDecisionLog;
        this.authService = authService;
        this.loanMapper = loanMapper;
        this.loanNotificationService = loanNotificationService;
        this.auditService = auditService;
        this.deductionCancellationService = deductionCancellationService;
        this.loanDocumentRepository = loanDocumentRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.workAssignmentGuard = workAssignmentGuard;
    }

    /**
     * Records why an application is held, in the caller's transaction: the loan is already saved with its
     * review PENDING, which is what keeps it from being lodged, or from being approved if it already was.
     */
    public void hold(Loan loan, List<PayslipFraudDetector.Finding> findings) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        payslipFraudFlagRepository.saveAll(findings.stream()
                .map(finding -> PayslipFraudFlag.builder()
                        .loanId(loan.getId())
                        .reason(finding.reason())
                        .matchedLoanId(finding.matchedLoanId())
                        .detail(finding.detail())
                        .createdAt(now)
                        .build())
                .toList());
        String reasons = findings.stream().map(finding -> finding.reason().name()).distinct()
                .collect(Collectors.joining(","));
        String matched = findings.stream().map(PayslipFraudDetector.Finding::matchedLoanId).filter(Objects::nonNull)
                .map(String::valueOf).collect(Collectors.joining(","));
        log.warn("PAYSLIP REVIEW REQUIRED: loan {} held back from SSB lodgement ({}; other loans {}) until a credit"
                + " officer clears or confirms it", loan.getReference(), reasons, matched.isEmpty() ? "none" : matched);
        audit(PAYSLIP_REVIEW_REQUIRED, loan, loan.getCreatedBy(),
                "reference=" + loan.getReference() + " reasons=" + reasons + " matchedLoans=" + matched);
    }

    /** The applications waiting for review, oldest first, each with every reason it is held. */
    @Transactional(readOnly = true)
    public List<PayslipReviewResponse> queue() {
        List<Loan> held = loanRepository.findByPayslipReviewStatusOrderByIdAsc(PayslipReviewStatus.PENDING);
        if (held.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PayslipFraudFlag>> flags = payslipFraudFlagRepository
                .findByLoanIdInOrderByIdAsc(held.stream().map(Loan::getId).toList()).stream()
                .collect(Collectors.groupingBy(PayslipFraudFlag::getLoanId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Loan> matched = loanRepository.findAllById(flags.values().stream().flatMap(List::stream)
                        .map(PayslipFraudFlag::getMatchedLoanId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Loan::getId, Function.identity()));
        return held.stream()
                .map(loan -> PayslipReviewResponse.of(loan, flags.getOrDefault(loan.getId(), List.of()).stream()
                        .map(flag -> PayslipFraudFlagResponse.of(flag,
                                flag.getMatchedLoanId() == null ? null : matched.get(flag.getMatchedLoanId())))
                        .toList()))
                .toList();
    }

    private record Reviewed(Loan loan, LoanResponse view) {
    }

    /**
     * Decides a held application and returns it as it now stands.
     *
     * @throws NotFoundException     no such loan
     * @throws ConflictException     the loan is not waiting for review
     * @throws AccessDeniedException the reviewer originated the loan, amended its documents or is a party to it,
     *                               and is clearing it
     */
    public LoanResponse review(Long loanId, PayslipReviewRequest request) {
        PayslipReviewStatus outcome = request.getOutcome();
        if (outcome == null || outcome == PayslipReviewStatus.PENDING) {
            throw new LoanApprovalException("Invalid outcome: a review must be CLEARED or CONFIRMED");
        }
        if (StringUtils.isBlank(request.getComment())) {
            throw new LoanApprovalException("Comment is required");
        }
        String comment = request.getComment().trim();
        String username = authService.getLoggedInUsername();

        Reviewed reviewed = Objects.requireNonNull(
                transactionTemplate.execute(tx -> reviewLocked(loanId, outcome, comment, username)));

        Loan loan = reviewed.loan();
        audit(outcome == PayslipReviewStatus.CLEARED ? PAYSLIP_REVIEW_CLEARED : PAYSLIP_REVIEW_CONFIRMED, loan,
                username, "reference=" + loan.getReference() + " outcome=" + outcome);
        if (outcome == PayslipReviewStatus.CONFIRMED) {
            // The same decline as any credit rejection: nothing says why.
            loanNotificationService.notify(loan, LoanNotice.DECLINED);
        }
        return reviewed.view();
    }

    private Reviewed reviewLocked(Long loanId, PayslipReviewStatus outcome, String comment, String username) {
        Loan loan = loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new NotFoundException("Loan " + loanId + " not found"));
        if (loan.getPayslipReviewStatus() != PayslipReviewStatus.PENDING) {
            throw new ConflictException(String.format("Loan %s has no payslip review pending", loan.getReference()));
        }
        // At an EXCLUSIVE stage, an assigned review is its assignee's to decide (FR-SSB-014).
        workAssignmentGuard.requireMayAct(SystemStage.PAYSLIP_REVIEW, loan, username);
        if (outcome == PayslipReviewStatus.CLEARED) {
            // Clearing lets the loan go on to be paid, so it is held to the same rule as approving it.
            // Confirming stops it, which anyone may do.
            requireNoConflictOfInterest(loan, username);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        loan.setPayslipReviewStatus(outcome);
        loan.setPayslipReviewedBy(username);
        loan.setPayslipReviewedAt(now);
        loan.setPayslipReviewComment(comment);
        if (outcome == PayslipReviewStatus.CONFIRMED) {
            loan.setInternalApprovalStatus(InternalApprovalStatus.REJECTED);
            loan.setInternalApprovalDate(now);
            loan.setInternalApprovalBy(username);
            loan.setInternalApprovalComment(comment);
            loan.setInternalApprovalReasonCode(CONFIRMED_REASON_CODE);
            if (DeductionCancellationService.wasLodged(loan)) {
                // Held by a payslip amended after lodgement (FR-SSB-009): the deduction is live at SSB for a
                // loan that will never be paid, as with any credit rejection.
                deductionCancellationService.markRequired(loan, DeductionCancellationService.REASON_CREDIT_REJECTED,
                        username, DeductionCancellationService.PORTAL_CHANNEL);
            }
        }
        Loan saved = loanRepository.save(loan);
        if (outcome == PayslipReviewStatus.CONFIRMED) {
            creditDecisionLog.record(saved, CreditAction.REJECTED, CONFIRMED_REASON_CODE, comment, username, now);
        }
        return new Reviewed(saved, loanMapper.toResponse(saved));
    }

    private void requireNoConflictOfInterest(Loan loan, String reviewer) {
        if (SegregationOfDuties.originated(loan, reviewer)) {
            throw new AccessDeniedException(String.format(
                    "Loan %s was originated by %s, who cannot also clear its payslip review;"
                            + " another credit officer must",
                    loan.getReference(), reviewer));
        }
        if (loanDocumentRepository.existsByLoanIdAndOriginAndUploadedByIgnoreCase(
                loan.getId(), DocumentOrigin.AMENDMENT, reviewer)) {
            throw new AccessDeniedException(String.format(
                    "Loan %s has documents amended by %s, who cannot also clear its payslip review;"
                            + " another credit officer must",
                    loan.getReference(), reviewer));
        }
        if (SegregationOfDuties.isPartyTo(loan, authService.getLoggedInUser())) {
            throw new AccessDeniedException(String.format(
                    "%s is a party to loan %s and cannot clear its payslip review; another credit officer must",
                    reviewer, loan.getReference()));
        }
    }

    private void audit(String eventType, Loan loan, String actor, String detail) {
        try {
            auditService.record(AuditLog.builder()
                    .eventType(eventType)
                    .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                    .actorId(actor).channelUsed(DeductionCancellationService.PORTAL_CHANNEL)
                    .detail(detail)
                    .correlationId(loan.getReference()));
        } catch (Exception ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while opening
            // the transaction; the change itself is saved and must stand.
            log.error("Audit of loan {} ({}) failed", loan.getId(), eventType, ex);
        }
    }
}
