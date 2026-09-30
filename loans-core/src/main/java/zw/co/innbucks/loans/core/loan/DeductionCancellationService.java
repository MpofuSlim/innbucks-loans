package zw.co.innbucks.loans.core.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.workflow.SystemStage;
import zw.co.innbucks.loans.core.workflow.WorkAssignmentGuard;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static zw.co.innbucks.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl.maskEcNumber;

/**
 * Tracks Ndasenda (SSB payroll) deductions that must be cancelled. A loan is lodged with Ndasenda
 * BEFORE the credit decision, so a loan that is then refused, or whose booking fails, still has a
 * live stop order on a civil servant's salary for money never paid out.
 *
 * <p>Tracking only, by owner decision: nothing here calls Ndasenda. A flagged loan is logged at
 * ERROR, audited and listed for operators, who cancel it on Ndasenda's own portal and record that
 * here. Sending the cancellation ourselves waits until its shape is confirmed against Ndasenda's
 * sandbox.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeductionCancellationService {

    static final String CANCELLATION_REQUIRED = "DEDUCTION_CANCELLATION_REQUIRED";
    static final String CANCELLATION_WITHDRAWN = "DEDUCTION_CANCELLATION_WITHDRAWN";
    static final String CANCELLED_EXTERNALLY = "DEDUCTION_CANCELLED_EXTERNALLY";

    /** Credit declined a loan whose deduction Ndasenda had already accepted. */
    public static final String REASON_CREDIT_REJECTED = "CREDIT_REJECTED";
    /** InnBucks answered and refused the booking, or its inquiry reported the loan FAILED: nothing was booked. */
    public static final String REASON_BOOKING_FAILED = "BOOKING_FAILED";
    /**
     * The booking or payout failed without InnBucks saying so (a timeout, a 5xx, a 409, an unreadable
     * answer, or a failure no writer classified). InnBucks books AND pays on one call, so the
     * customer may hold the loan: cancelling its repayment before checking would be the worse error.
     */
    public static final String REASON_BOOKING_IN_DOUBT = "BOOKING_IN_DOUBT";
    /**
     * The lodgement reached Ndasenda, but a later step threw and the loan was marked FAILED. No longer
     * set: the lodgement job now keeps such a loan PROCESSING; kept for loans flagged before that.
     */
    public static final String REASON_LODGEMENT_FAILED = "LODGEMENT_FAILED";
    /** Ndasenda reported the deduction accepted for a loan we had already closed (declined or FAILED). */
    public static final String REASON_ACCEPTED_AFTER_CLOSE = "ACCEPTED_AFTER_CLOSE";

    static final String PORTAL_CHANNEL = "admin-portal";

    private final LoanRepository loanRepository;
    private final AuditService auditService;
    private final AuthService authService;
    private final WorkAssignmentGuard workAssignmentGuard;
    private final MarketTimeZone marketTimeZone;

    /** What the operator must do about a flag, shown in its ERROR line and in the queue. */
    public static String operatorAction(String reason) {
        if (REASON_BOOKING_IN_DOUBT.equals(reason)) {
            return "The InnBucks booking failed without a definitive answer, so the customer may hold this loan:"
                    + " confirm with InnBucks that no loan was booked under this reference before cancelling"
                    + " the deduction on Ndasenda's portal";
        }
        return "The loan will not be paid but its payroll deduction reached Ndasenda:"
                + " cancel the deduction on Ndasenda's portal";
    }

    /**
     * Who recorded a loan's deduction as cancelled on Ndasenda's portal, and when, for the text of a refusal:
     * {@code by loans.admin at 2026-09-21T12:00:00+02:00}. The stored UTC time at the market offset, to the second, as
     * the JSON fields give it; formatted directly it read {@code 2026-09-21T10:00:00.123456}, UTC with no offset.
     */
    public String describeRecordedCancellation(Loan loan) {
        return "by " + loan.getDeductionCancelledBy() + " at " + marketTimeZone.render(loan.getDeductionCancelledAt());
    }

    /** A batch number, or Ndasenda's own id for the deduction, is the evidence a lodgement reached Ndasenda. */
    public static boolean wasLodged(Loan loan) {
        return StringUtils.isNotBlank(loan.getBatchNumber()) || StringUtils.isNotBlank(loan.getApprovalReference());
    }

    /**
     * Flags a loan that will not be paid although its deduction reached Ndasenda; callers flag only
     * with that evidence in hand ({@link #wasLodged}, a credit decision that requires Ndasenda's
     * acceptance, or Ndasenda's own report). Idempotent: a loan already flagged keeps its first
     * reason and time, and one already cancelled is never re-flagged. Only mutates the loan; the
     * caller saves it. The audit row commits on its own (REQUIRES_NEW) before that save, so a save
     * that then fails leaves an audit row for a flag that did not persist.
     *
     * @return whether this call set the flag
     */
    public boolean markRequired(Loan loan, String reason, String actor, String channel) {
        if (loan.getDeductionCancellationStatus() != null) {
            return false;
        }
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.REQUIRED);
        loan.setDeductionCancellationReason(reason);
        loan.setDeductionCancellationRequestedAt(LocalDateTime.now(ZoneOffset.UTC));

        log.error("DEDUCTION CANCELLATION REQUIRED ({}): loan {} reference {} batch {} ec {} instalment {}"
                        + " - {}, then record it (audited)",
                reason, loan.getId(), loan.getReference(), loan.getBatchNumber(),
                maskEcNumber(loan.getEcNumber()), loan.getGrossedMonthlyDeduction(), operatorAction(reason));
        audit(CANCELLATION_REQUIRED, loan, actor, channel, null, DeductionCancellationStatus.REQUIRED,
                "reason=" + reason, null);
        return true;
    }

    /**
     * Ndasenda has now answered a lodgement we had given up on (a FAILED loan). Refused, there is
     * nothing to cancel; accepted, the loan is back in the credit queue and a cancellation would
     * leave a paid loan with no repayment. Either way the flag no longer holds; a later refusal
     * flags it again. A cancellation already recorded is never undone here. Only mutates the loan.
     */
    public void withdraw(Loan loan, String ndasendaOutcome, String actor) {
        if (loan.getDeductionCancellationStatus() != DeductionCancellationStatus.REQUIRED) {
            return;
        }
        String reason = loan.getDeductionCancellationReason();
        loan.setDeductionCancellationStatus(null);
        loan.setDeductionCancellationReason(null);
        loan.setDeductionCancellationRequestedAt(null);

        log.warn("DEDUCTION CANCELLATION WITHDRAWN: loan {} reference {} ec {} was flagged {} but Ndasenda has now"
                        + " answered {}, which settles it - if the deduction was already cancelled on Ndasenda's"
                        + " portal without being recorded here, the loan must not be paid (audited)",
                loan.getId(), loan.getReference(), maskEcNumber(loan.getEcNumber()), reason, ndasendaOutcome);
        audit(CANCELLATION_WITHDRAWN, loan, actor, "system", DeductionCancellationStatus.REQUIRED, null,
                "reason=" + reason + " ndasendaOutcome=" + ndasendaOutcome, null);
    }

    /**
     * A recovery payout is about to pay a loan flagged for cancellation, and a paid loan must keep
     * its repayment. The flag comes off BEFORE InnBucks is called, under the payout's row lock, so the
     * loan leaves the operator queue and nobody can record its deduction cancelled while money may be
     * moving; the payout flags it again if nothing was paid or it cannot tell. Only mutates the loan.
     *
     * @return the reason the loan was flagged with, or null if it was not flagged
     */
    public String withdrawForPayout(Loan loan, String payoutReference) {
        if (loan.getDeductionCancellationStatus() != DeductionCancellationStatus.REQUIRED) {
            return null;
        }
        String reason = loan.getDeductionCancellationReason();
        loan.setDeductionCancellationStatus(null);
        loan.setDeductionCancellationReason(null);
        loan.setDeductionCancellationRequestedAt(null);

        String username = authService.getLoggedInUsername();
        log.warn("DEDUCTION CANCELLATION WITHDRAWN: loan {} reference {} ec {} was flagged {} and is being paid by"
                        + " manual payout {} - its deduction must stay; flagged again if that payout does not pay"
                        + " (audited)",
                loan.getId(), loan.getReference(), maskEcNumber(loan.getEcNumber()), reason, payoutReference);
        audit(CANCELLATION_WITHDRAWN, loan, username, PORTAL_CHANNEL, DeductionCancellationStatus.REQUIRED, null,
                "reason=" + reason + " manualPayout=" + payoutReference, null);
        return reason;
    }

    /** Loans whose deduction still has to be cancelled, oldest first. */
    @Transactional(readOnly = true)
    public List<DeductionCancellationResponse> findRequired() {
        return loanRepository
                .findByDeductionCancellationStatusOrderByDeductionCancellationRequestedAtAscIdAsc(
                        DeductionCancellationStatus.REQUIRED)
                .stream()
                .map(DeductionCancellationResponse::from)
                .toList();
    }

    /**
     * An operator records that they cancelled the deduction on Ndasenda's own portal. Row-locked,
     * so two operators recording the same loan get one success and one 409, not a double record.
     */
    @Transactional
    public DeductionCancellationResponse markCancelledExternally(Long loanId, String note) {
        Loan loan = loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new NotFoundException("Loan " + loanId + " not found"));

        if (loan.getDeductionCancellationStatus() == DeductionCancellationStatus.CANCELLED_EXTERNALLY) {
            throw new ConflictException(String.format("Loan %d's deduction was already recorded as cancelled %s",
                    loanId, describeRecordedCancellation(loan)));
        }
        if (loan.getDeductionCancellationStatus() != DeductionCancellationStatus.REQUIRED) {
            throw new ConflictException(String.format("Loan %d has no deduction cancellation pending", loanId));
        }

        String username = authService.getLoggedInUsername();
        // At an EXCLUSIVE stage, an assigned cancellation is its assignee's to record (FR-SSB-014).
        workAssignmentGuard.requireMayAct(SystemStage.DEDUCTION_CANCELLATION, loan, username);
        String cleanNote = StringUtils.strip(note);
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.CANCELLED_EXTERNALLY);
        loan.setDeductionCancellationNote(cleanNote);
        loan.setDeductionCancelledBy(username);
        loan.setDeductionCancelledAt(LocalDateTime.now(ZoneOffset.UTC));
        Loan saved = loanRepository.save(loan);

        log.info("Deduction for loan {} reference {} recorded as cancelled on Ndasenda's portal by {}",
                loan.getId(), loan.getReference(), username);
        // The note is free text: pinned by its hash, not copied into the audit trail.
        audit(CANCELLED_EXTERNALLY, loan, username, PORTAL_CHANNEL, DeductionCancellationStatus.REQUIRED,
                DeductionCancellationStatus.CANCELLED_EXTERNALLY,
                "reason=" + loan.getDeductionCancellationReason(), AuditService.sha256Hex(cleanNote));
        return DeductionCancellationResponse.from(saved);
    }

    private void audit(String eventType, Loan loan, String actor, String channel,
                       DeductionCancellationStatus from, DeductionCancellationStatus to,
                       String outcome, String payloadHash) {
        try {
            // Identifiers only, as for the NDASENDA_* events: the national ID is never copied here
            // and the EC number keeps its last 3 characters. Correlated on the Ndasenda batch.
            auditService.record(AuditLog.builder()
                    .eventType(eventType)
                    .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                    .actorId(actor).channelUsed(channel)
                    .stateTransitionDelta("{\"from\":" + quoted(from) + ",\"to\":" + quoted(to) + "}")
                    .detail(outcome + " reference=" + loan.getReference() + " batch=" + loan.getBatchNumber()
                            + " instalment=" + loan.getGrossedMonthlyDeduction()
                            + " ecNumber=" + maskEcNumber(loan.getEcNumber()))
                    .payloadHash(payloadHash)
                    .correlationId(loan.getBatchNumber()));
        } catch (Exception ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while
            // opening the transaction; the log line above is the evidence, and the caller must carry on.
            log.error("Audit of loan {} ({}) failed", loan.getId(), eventType, ex);
        }
    }

    private static String quoted(DeductionCancellationStatus status) {
        return status == null ? "null" : "\"" + status.name() + "\"";
    }
}
