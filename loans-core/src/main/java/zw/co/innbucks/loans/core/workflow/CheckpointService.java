package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.CreditDecisionLog;
import zw.co.innbucks.loans.core.loan.CreditReasonCode;
import zw.co.innbucks.loans.core.loan.CreditReasonCodeRepository;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.SegregationOfDuties;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.user.User;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Clears loans at checkpoints, or declines them there (FR-SSB-014). A decline is a credit rejection like any other: it
 * carries a credit reason code, goes in the credit decision log, flags a lodged SSB deduction for cancellation and
 * tells the applicant. Whoever decides must work the checkpoint, and may not be the loan's originator or a party to it,
 * nor, at a checkpoint after Credit's approval such as the payout authorisation (FR-SSB-018), whoever approved it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckpointService {

    static final String CHECKPOINT_CLEARED = "CHECKPOINT_CLEARED";
    static final String CHECKPOINT_DECLINED = "CHECKPOINT_DECLINED";
    private static final String CHANNEL = "admin-portal";

    private final WorkflowStageService workflowStageService;
    private final WorkflowStageRepository workflowStageRepository;
    private final StageQueues stageQueues;
    private final CheckpointDecisionRepository checkpointDecisionRepository;
    private final WorkItemRepository workItemRepository;
    private final WorkAssignmentGuard workAssignmentGuard;
    private final LoanRepository loanRepository;
    private final CreditReasonCodeRepository creditReasonCodeRepository;
    private final CreditDecisionLog creditDecisionLog;
    private final DeductionCancellationService deductionCancellationService;
    private final LoanNotificationService loanNotificationService;
    private final AuthService authService;
    private final AuditService auditService;

    /**
     * Records how the loan leaves the checkpoint, once.
     *
     * @throws NotFoundException        no such checkpoint, or no such loan
     * @throws ConflictException        the checkpoint is not holding the loan (not at its point, not one it applies to,
     *                                  already decided, or inactive), or the loan is assigned to someone else at an
     *                                  EXCLUSIVE checkpoint
     * @throws AccessDeniedException    the caller originated the loan or is a party to it, or approved it at Credit and
     *                                  the checkpoint follows that approval
     * @throws IllegalArgumentException a decline without an active REJECTED reason code
     */
    @Transactional
    public CheckpointDecisionResponse decide(String code, Long loanId, CheckpointDecisionRequest request) {
        WorkflowStage stage = checkpoint(code);
        Loan loan = loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new NotFoundException("Loan " + loanId + " not found"));
        LocalDateTime entered = stageQueues.of(stage).enteredAt(loan).orElseThrow(() -> new ConflictException(
                "Loan " + loan.getReference() + " is not waiting at " + stage.getName()));
        String username = authService.getLoggedInUsername();
        workAssignmentGuard.requireMayAct(stage, loan, username);
        requireNoConflictOfInterest(stage, loan, username);
        String comment = request.getComment().trim();
        CreditReasonCode reason = request.getOutcome() == CheckpointOutcome.DECLINED
                ? requireDeclineReason(request.getReasonCode()) : null;

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        CheckpointDecision decision = checkpointDecisionRepository.save(CheckpointDecision.builder()
                .stageCode(stage.getCode()).loanId(loan.getId()).enteredAt(entered)
                .outcome(request.getOutcome()).reasonCode(reason == null ? null : reason.getCode())
                .comment(comment).decidedBy(username).decidedAt(now)
                .build());
        if (reason != null) {
            decline(stage, loan, reason, comment, username, now);
        }
        log.info("Loan {} {} at checkpoint {} by {}", loan.getReference(),
                request.getOutcome() == CheckpointOutcome.CLEARED ? "cleared" : "declined", stage.getCode(), username);
        audit(request.getOutcome() == CheckpointOutcome.CLEARED ? CHECKPOINT_CLEARED : CHECKPOINT_DECLINED, loan,
                username, "stage=" + stage.getCode() + " holdPoint=" + stage.getHoldPoint()
                        + (reason == null ? "" : " reasonCode=" + reason.getCode()));
        return CheckpointDecisionResponse.of(decision, stage, loan.getReference());
    }

    /**
     * The loan's checkpoints: those holding it now, then every decision on it, oldest first.
     *
     * @throws NotFoundException no such loan
     */
    @Transactional(readOnly = true)
    public List<LoanCheckpointResponse> forLoan(Long loanId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new NotFoundException("Loan " + loanId + " not found"));
        Map<String, WorkflowStage> checkpoints = workflowStageRepository
                .findByKindOrderByDisplayOrderAscCodeAsc(StageKind.CHECKPOINT).stream()
                .collect(Collectors.toMap(WorkflowStage::getCode, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        List<LoanCheckpointResponse> responses = new ArrayList<>();
        for (WorkflowStage stage : checkpoints.values()) {
            Optional<LocalDateTime> entered = stageQueues.of(stage).enteredAt(loan);
            if (entered.isEmpty()) {
                continue;
            }
            String assignedTo = workItemRepository.findByStageCodeAndLoanIdAndEnteredAt(stage.getCode(), loanId,
                    entered.get()).map(WorkItem::getAssignedTo).orElse(null);
            responses.add(new LoanCheckpointResponse(stage.getCode(), stage.getName(), stage.getHoldPoint(),
                    CheckpointStatus.PENDING, entered.get(), assignedTo, null, null, null, null));
        }
        for (CheckpointDecision decision : checkpointDecisionRepository.findByLoanIdOrderByIdAsc(loanId)) {
            WorkflowStage stage = checkpoints.get(decision.getStageCode());
            responses.add(new LoanCheckpointResponse(decision.getStageCode(),
                    stage == null ? decision.getStageCode() : stage.getName(),
                    stage == null ? null : stage.getHoldPoint(),
                    CheckpointStatus.valueOf(decision.getOutcome().name()), decision.getEnteredAt(), null,
                    decision.getReasonCode(), decision.getComment(), decision.getDecidedBy(), decision.getDecidedAt()));
        }
        return responses;
    }

    private WorkflowStage checkpoint(String code) {
        WorkflowStage stage = workflowStageService.stage(code);
        if (!stage.isCheckpoint()) {
            throw new NotFoundException("No checkpoint stage " + code);
        }
        return stage;
    }

    /**
     * Declines the application as a credit rejection, in the decision log like any other; a deduction already lodged
     * with SSB is flagged for cancellation. The applicant is sent the decline, which says nothing of why, once this
     * commits.
     */
    private void decline(WorkflowStage stage, Loan loan, CreditReasonCode reason, String comment, String username,
                         LocalDateTime now) {
        String logged = StringUtils.left(stage.getName() + ": " + comment, 255);
        loan.setInternalApprovalStatus(InternalApprovalStatus.REJECTED);
        loan.setInternalApprovalDate(now);
        loan.setInternalApprovalBy(username);
        loan.setInternalApprovalComment(logged);
        loan.setInternalApprovalReasonCode(reason.getCode());
        if (DeductionCancellationService.wasLodged(loan)) {
            deductionCancellationService.markRequired(loan, DeductionCancellationService.REASON_CREDIT_REJECTED,
                    username, CHANNEL);
        }
        Loan saved = loanRepository.save(loan);
        creditDecisionLog.record(saved, CreditAction.REJECTED, reason.getCode(), logged, username, now);
        loanNotificationService.notify(saved, LoanNotice.DECLINED);
    }

    private CreditReasonCode requireDeclineReason(String requested) {
        if (StringUtils.isBlank(requested)) {
            throw new IllegalArgumentException("A reason code is required to decline");
        }
        String code = requested.trim().toUpperCase(Locale.ROOT);
        CreditReasonCode reason = creditReasonCodeRepository.findById(code)
                .filter(CreditReasonCode::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Unknown reason code " + code));
        if (reason.getDecision() != InternalApprovalStatus.REJECTED) {
            throw new IllegalArgumentException(String.format("Reason code %s is for %s decisions, not a decline",
                    code, reason.getDecision()));
        }
        return reason;
    }

    private void requireNoConflictOfInterest(WorkflowStage stage, Loan loan, String username) {
        if (SegregationOfDuties.originated(loan, username)) {
            throw new AccessDeniedException(String.format(
                    "Loan %s was originated by %s, who cannot also decide its %s; someone else must",
                    loan.getReference(), username, stage.getName()));
        }
        if (stage.barsCreditApprover() && SegregationOfDuties.approved(loan, username)) {
            throw new AccessDeniedException(String.format(
                    "Loan %s was approved by %s, who cannot also decide its %s; someone else must",
                    loan.getReference(), username, stage.getName()));
        }
        User user = authService.getLoggedInUser();
        if (SegregationOfDuties.isPartyTo(loan, user)) {
            throw new AccessDeniedException(String.format(
                    "%s is a party to loan %s and cannot decide its %s; someone else must",
                    username, loan.getReference(), stage.getName()));
        }
    }

    private void audit(String eventType, Loan loan, String username, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(eventType)
                .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                .actorId(username).channelUsed(CHANNEL)
                .detail(detail)
                .correlationId(loan.getReference()));
    }
}
