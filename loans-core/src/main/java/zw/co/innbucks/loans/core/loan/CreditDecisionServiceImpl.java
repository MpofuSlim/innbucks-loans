package zw.co.innbucks.loans.core.loan;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.document.DocumentOrigin;
import zw.co.innbucks.loans.core.document.LoanDocumentRepository;
import zw.co.innbucks.loans.core.exception.LoanApprovalException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.workflow.SystemStage;
import zw.co.innbucks.loans.core.workflow.CheckpointGate;
import zw.co.innbucks.loans.core.workflow.HoldPoint;
import zw.co.innbucks.loans.core.workflow.WorkAssignmentGuard;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static zw.co.innbucks.loans.core.merchant.MerchantService.maskAccountNumber;

@Slf4j
@Service
public class CreditDecisionServiceImpl implements CreditDecisionService {

    static final String CREDIT_APPROVED = "CREDIT_APPROVED";

    private final LoanRepository loanRepository;
    private final AuthService authService;
    private final LoanMapper loanMapper;
    private final LoanNotificationService loanNotificationService;
    private final DeductionCancellationService deductionCancellationService;
    private final AuditService auditService;
    private final CreditDecisionRepository creditDecisionRepository;
    private final CreditReasonCodeRepository creditReasonCodeRepository;
    private final CreditDecisionLog creditDecisionLog;
    private final LoanDocumentRepository loanDocumentRepository;
    private final TransactionTemplate transactionTemplate;
    private final WorkAssignmentGuard workAssignmentGuard;
    private final CheckpointGate checkpointGate;

    public CreditDecisionServiceImpl(LoanRepository loanRepository, AuthService authService, LoanMapper loanMapper,
                                     LoanNotificationService loanNotificationService,
                                     DeductionCancellationService deductionCancellationService,
                                     AuditService auditService, CreditDecisionRepository creditDecisionRepository,
                                     CreditReasonCodeRepository creditReasonCodeRepository,
                                     CreditDecisionLog creditDecisionLog,
                                     LoanDocumentRepository loanDocumentRepository,
                                     PlatformTransactionManager transactionManager,
                                     WorkAssignmentGuard workAssignmentGuard,
                                     CheckpointGate checkpointGate) {
        this.loanRepository = loanRepository;
        this.authService = authService;
        this.loanMapper = loanMapper;
        this.loanNotificationService = loanNotificationService;
        this.deductionCancellationService = deductionCancellationService;
        this.auditService = auditService;
        this.creditDecisionRepository = creditDecisionRepository;
        this.creditReasonCodeRepository = creditReasonCodeRepository;
        this.creditDecisionLog = creditDecisionLog;
        this.loanDocumentRepository = loanDocumentRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.workAssignmentGuard = workAssignmentGuard;
        this.checkpointGate = checkpointGate;
    }

    /** What a committed decision leaves for the steps that run after it. */
    private record Decided(Loan loan, LoanResponse view, PayoutDestination payee) {
    }

    @Override
    public LoanResponse decide(Long id, CreditDecisionRequest request) {
        InternalApprovalStatus decision = request.getDecision();
        if (decision == null || decision == InternalApprovalStatus.PENDING) {
            throw new LoanApprovalException("Invalid status: a decision must be APPROVED, REJECTED or RETURNED");
        }
        String username = authService.getLoggedInUsername();

        // The loan row is locked for the decision, and the loan update and its log entry commit together:
        // a second officer deciding the same loan waits, then finds it decided.
        Decided decided = Objects.requireNonNull(
                transactionTemplate.execute(tx -> decideLocked(id, request, username)));

        if (decided.payee() != null) {
            auditApproval(decided.loan(), username, decided.payee());
        }
        // Reference and amount only. The comment is the reviewer's internal note, stored for staff.
        loanNotificationService.notify(decided.loan(), switch (decision) {
            case APPROVED -> LoanNotice.APPROVED;
            case REJECTED -> LoanNotice.DECLINED;
            default -> LoanNotice.MORE_INFORMATION_NEEDED;
        });
        return decided.view();
    }

    private Decided decideLocked(Long id, CreditDecisionRequest request, String username) {
        InternalApprovalStatus decision = request.getDecision();
        Loan loan = loanRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Loan " + id + " not found"));

        InternalApprovalStatus current = loan.getInternalApprovalStatus();
        if (current == InternalApprovalStatus.RETURNED && decision != InternalApprovalStatus.REJECTED) {
            // Waiting on the originator. Rejecting it is still allowed, so an unanswered return can be closed.
            throw new LoanApprovalException(
                    "Loan was returned for more information and has not been resubmitted; it can only be rejected");
        }
        if (current != null && current != InternalApprovalStatus.PENDING && current != InternalApprovalStatus.RETURNED) {
            throw new LoanApprovalException(String.format("Loan has already been %s", current.name().toLowerCase()));
        }
        if (loan.getLoanApprovalStatus() != LoanApprovalStatus.APPROVED) {
            throw new LoanApprovalException(String.format("Loan with status %s cannot be approved",
                    loan.getLoanApprovalStatus()));
        }
        // At an EXCLUSIVE stage, an assigned loan is its assignee's to decide (FR-SSB-014).
        workAssignmentGuard.requireMayAct(SystemStage.CREDIT_DECISION, loan, username);
        CreditReasonCode reason = requireReasonCode(request.getReasonCode(), decision);
        String comment = requireComment(request.getComment());

        PayoutDestination payee = null;
        if (decision == InternalApprovalStatus.APPROVED) {
            if (loan.getPayslipReviewStatus() == PayslipReviewStatus.PENDING) {
                // Held by a payslip amended after SSB accepted the loan (FR-SSB-007/009): review it first.
                throw new LoanApprovalException(String.format(
                        "Loan %s is held for payslip review and cannot be approved until it is cleared",
                        loan.getReference()));
            }
            if (loanRepository.isHeldForEmploymentEvent(loan.getId())) {
                // Held by an employment event (FR-SSB-024): an officer releases or declines it first.
                throw new LoanApprovalException(String.format(
                        "Loan %s is held for an employment event and cannot be approved until it is released",
                        loan.getReference()));
            }
            Optional<WorkflowStage> checkpoint = checkpointGate.holding(HoldPoint.BEFORE_CREDIT_APPROVAL, loan);
            if (checkpoint.isPresent()) {
                // Held at a checkpoint (FR-SSB-014): cleared there first. It can still be rejected or returned.
                throw new LoanApprovalException(String.format(
                        "Loan %s is held at %s and cannot be approved until it is cleared there",
                        loan.getReference(), checkpoint.get().getName()));
            }
            requireNoConflictOfInterest(loan, username);
            payee = requirePayee(loan);
            // Frozen with the decision: booking and any recovery payout pay this, not whatever the
            // merchant row says by the time the loan is booked.
            loan.setApprovedDisbursementType(payee.type());
            loan.setApprovedSettlementAccount(payee.merchantAccount());
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        loan.setInternalApprovalStatus(decision);
        loan.setInternalApprovalDate(now);
        loan.setInternalApprovalComment(comment);
        loan.setInternalApprovalBy(username);
        loan.setInternalApprovalReasonCode(reason.getCode());
        if (decision == InternalApprovalStatus.REJECTED) {
            // Lodged with Ndasenda before this decision (only an Ndasenda-APPROVED loan reaches it),
            // so the refusal leaves a live payroll deduction for a loan that will never be paid.
            deductionCancellationService.markRequired(loan, DeductionCancellationService.REASON_CREDIT_REJECTED,
                    username, DeductionCancellationService.PORTAL_CHANNEL);
        }
        Loan saved = loanRepository.save(loan);
        creditDecisionLog.record(saved, CreditAction.valueOf(decision.name()), reason.getCode(), comment, username,
                now);
        return new Decided(saved, loanMapper.toResponse(saved), payee);
    }

    @Override
    public LoanResponse resubmit(Long id, CreditResubmissionRequest request, LoanReadScope scope) {
        String username = authService.getLoggedInUsername();
        String comment = requireComment(request.getComment());
        return Objects.requireNonNull(transactionTemplate.execute(tx -> {
            if (!scope.platformWide() && !loanRepository.exists(LoanSpecification.readableBy(id, scope))) {
                // Out of scope reads as missing, as on every other loan read.
                throw new NotFoundException("Loan " + id + " not found");
            }
            Loan loan = loanRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new NotFoundException("Loan " + id + " not found"));
            if (loan.getInternalApprovalStatus() != InternalApprovalStatus.RETURNED) {
                throw new LoanApprovalException(String.format(
                        "Loan is not waiting for more information (credit status %s)", loan.getInternalApprovalStatus()));
            }
            // Back in the credit queue as an undecided loan; what was asked and answered is in the log. Its wait for
            // a decision starts again from now, as a new work item with no assignee and no escalation (FR-PBL-030).
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            loan.setInternalApprovalStatus(InternalApprovalStatus.PENDING);
            loan.setInternalApprovalDate(null);
            loan.setInternalApprovalComment(null);
            loan.setInternalApprovalBy(null);
            loan.setInternalApprovalReasonCode(null);
            loan.setCreditResubmittedAt(now);
            Loan saved = loanRepository.save(loan);
            creditDecisionLog.record(saved, CreditAction.RESUBMITTED, null, comment, username, now);
            // Back with Credit: the applicant is told once this commits (FR-SSB-016).
            loanNotificationService.notify(saved, LoanNotice.RESUBMITTED);
            LoanResponse view = loanMapper.toResponse(saved);
            return scope.platformWide() ? view : view.withoutPayslipReview();
        }));
    }

    @Override
    public List<CreditDecisionResponse> history(Long id) {
        if (!loanRepository.existsById(id)) {
            throw new NotFoundException("Loan " + id + " not found");
        }
        List<CreditDecision> entries = creditDecisionRepository.findByLoanIdOrderByIdAsc(id);
        Map<String, String> descriptions = creditReasonCodeRepository.findAllById(entries.stream()
                        .map(CreditDecision::getReasonCode).filter(StringUtils::isNotBlank).distinct().toList())
                .stream().collect(Collectors.toMap(CreditReasonCode::getCode, CreditReasonCode::getDescription));
        return entries.stream()
                .map(entry -> CreditDecisionResponse.of(entry, descriptions.get(entry.getReasonCode())))
                .toList();
    }

    @Override
    public List<CreditReasonCodeResponse> reasonCodes(InternalApprovalStatus decision) {
        List<CreditReasonCode> codes = decision == null
                ? creditReasonCodeRepository.findByActiveTrueOrderByDecisionAscDisplayOrderAsc()
                : creditReasonCodeRepository.findByActiveTrueAndDecisionOrderByDisplayOrderAsc(decision);
        return codes.stream().map(CreditReasonCodeResponse::of).toList();
    }

    /** Every credit action carries one (FR-PBL-027); the request's validation says so first. */
    private static String requireComment(String comment) {
        if (StringUtils.isBlank(comment)) {
            throw new LoanApprovalException("Comment is required");
        }
        return comment.trim();
    }

    /** An active code, belonging to the decision being made. */
    private CreditReasonCode requireReasonCode(String requested, InternalApprovalStatus decision) {
        if (StringUtils.isBlank(requested)) {
            throw new LoanApprovalException("Reason code is required");
        }
        String code = requested.trim().toUpperCase(Locale.ROOT);
        CreditReasonCode reason = creditReasonCodeRepository.findById(code)
                .filter(CreditReasonCode::isActive)
                .orElseThrow(() -> new LoanApprovalException("Unknown reason code " + code));
        if (reason.getDecision() != decision) {
            throw new LoanApprovalException(String.format("Reason code %s is for %s decisions, not %s",
                    code, reason.getDecision(), decision));
        }
        return reason;
    }

    /**
     * Segregation of duties (FR-PBL-029): nobody approves a loan they originated, resubmitted, replaced a
     * document of, or are a party to, since that one person would then decide both what is assessed and
     * that it passes. A refusal or a return is not held to it: neither pays anything.
     */
    private void requireNoConflictOfInterest(Loan loan, String approver) {
        String reference = loan.getReference();
        if (SegregationOfDuties.originated(loan, approver)) {
            throw new AccessDeniedException(String.format(
                    "Loan %s was originated by %s, who cannot also approve it; another credit officer must",
                    reference, approver));
        }
        if (creditDecisionRepository.existsByLoanIdAndActionAndPerformedByIgnoreCase(
                loan.getId(), CreditAction.RESUBMITTED, approver)) {
            throw new AccessDeniedException(String.format(
                    "Loan %s was resubmitted by %s, who cannot also approve it; another credit officer must",
                    reference, approver));
        }
        if (loanDocumentRepository.existsByLoanIdAndOriginAndUploadedByIgnoreCase(
                loan.getId(), DocumentOrigin.AMENDMENT, approver)) {
            throw new AccessDeniedException(String.format(
                    "Loan %s has documents amended by %s, who cannot also approve it; another credit officer must",
                    reference, approver));
        }
        if (SegregationOfDuties.isPartyTo(loan, authService.getLoggedInUser())) {
            throw new AccessDeniedException(String.format(
                    "%s is a party to loan %s and cannot approve it; another credit officer must", approver, reference));
        }
    }

    /** Refused before anything is recorded: a loan with nowhere to pay would only fail at booking. */
    private static PayoutDestination requirePayee(Loan loan) {
        Merchant merchant = loan.getMerchant();
        PayoutDestination payee = PayoutDestination.live(merchant);
        String reference = loan.getReference();
        if (payee.type() == null) {
            throw new LoanApprovalException(String.format(
                    "Loan %s has no merchant payout type, so there is nowhere to pay it", reference));
        }
        if (payee.paysMerchant() && StringUtils.isBlank(payee.merchantAccount())) {
            throw new LoanApprovalException(String.format(
                    "Merchant %s has no settlement account, so loan %s cannot be paid", merchant.getCompanyName(), reference));
        }
        if (!payee.paysMerchant() && StringUtils.isBlank(loan.payoutWalletNumber())) {
            throw new LoanApprovalException(String.format(
                    "Loan %s has no customer wallet number to pay", reference));
        }
        return payee;
    }

    private void auditApproval(Loan loan, String approver, PayoutDestination payee) {
        try {
            // Who approved, and the destination frozen with it: the account masked, as everywhere else.
            auditService.record(AuditLog.builder()
                    .eventType(CREDIT_APPROVED)
                    .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                    .actorId(approver).channelUsed(DeductionCancellationService.PORTAL_CHANNEL)
                    .detail("reference=" + loan.getReference() + " originator=" + loan.getCreatedBy()
                            + " payoutType=" + payee.type()
                            + (payee.paysMerchant() ? " settlementAccount=" + maskAccountNumber(payee.merchantAccount())
                            + " merchant=" + (loan.getMerchant() == null ? null : loan.getMerchant().getMerchantCode()) : "")
                            + " amount=" + loan.getDisbursedAmount())
                    .correlationId(loan.getReference()));
        } catch (Exception ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while
            // opening the transaction; the approval itself is saved and must stand.
            log.error("Audit of loan {} ({}) failed", loan.getId(), CREDIT_APPROVED, ex);
        }
    }
}
