package zw.co.reikan.loans.core.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.exception.ConflictException;
import zw.co.reikan.loans.core.exception.NotFoundException;

import java.time.LocalDateTime;
import java.util.List;

import static zw.co.reikan.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl.maskEcNumber;

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
    /** InnBucks booking/payout failed definitively and the saga compensated. */
    public static final String REASON_BOOKING_FAILED = "BOOKING_FAILED";
    /** The lodgement reached Ndasenda, but a later step threw and the loan was marked FAILED. */
    public static final String REASON_LODGEMENT_FAILED = "LODGEMENT_FAILED";
    /** Ndasenda reported the deduction accepted for a loan we had already closed (declined or FAILED). */
    public static final String REASON_ACCEPTED_AFTER_CLOSE = "ACCEPTED_AFTER_CLOSE";

    static final String PORTAL_CHANNEL = "admin-portal";

    private final LoanRepository loanRepository;
    private final AuditService auditService;
    private final AuthService authService;

    /** A batch number, or Ndasenda's own id for the deduction, is the evidence a lodgement reached Ndasenda. */
    public static boolean wasLodged(Loan loan) {
        return StringUtils.isNotBlank(loan.getBatchNumber()) || StringUtils.isNotBlank(loan.getApprovalReference());
    }

    /**
     * Flags a loan that will not be paid although its deduction reached Ndasenda; callers flag only
     * with that evidence in hand ({@link #wasLodged}, a credit decision that requires Ndasenda's
     * acceptance, or Ndasenda's own report). Idempotent: a loan already flagged keeps its first
     * reason and time, and one already cancelled is never re-flagged. Only mutates the loan; the
     * caller saves it.
     *
     * @return whether this call set the flag
     */
    public boolean markRequired(Loan loan, String reason, String actor, String channel) {
        if (loan.getDeductionCancellationStatus() != null) {
            return false;
        }
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.REQUIRED);
        loan.setDeductionCancellationReason(reason);
        loan.setDeductionCancellationRequestedAt(LocalDateTime.now());

        log.error("DEDUCTION CANCELLATION REQUIRED ({}): loan {} reference {} batch {} ec {} instalment {}"
                        + " - the loan will not be paid but its payroll deduction reached Ndasenda;"
                        + " cancel it on Ndasenda's portal and record it (audited)",
                reason, loan.getId(), loan.getReference(), loan.getBatchNumber(),
                maskEcNumber(loan.getEcNumber()), loan.getGrossedMonthlyDeduction());
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

    /** Loans whose deduction still has to be cancelled, oldest first. */
    @Transactional(readOnly = true)
    public List<DeductionCancellationDto> findRequired() {
        return loanRepository
                .findByDeductionCancellationStatusOrderByDeductionCancellationRequestedAtAscIdAsc(
                        DeductionCancellationStatus.REQUIRED)
                .stream()
                .map(DeductionCancellationDto::from)
                .toList();
    }

    /**
     * An operator records that they cancelled the deduction on Ndasenda's own portal. Row-locked,
     * so two operators recording the same loan get one success and one 409, not a double record.
     */
    @Transactional
    public DeductionCancellationDto markCancelledExternally(Long loanId, String note) {
        Loan loan = loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new NotFoundException("Loan " + loanId + " not found"));

        if (loan.getDeductionCancellationStatus() == DeductionCancellationStatus.CANCELLED_EXTERNALLY) {
            throw new ConflictException(String.format("Loan %d's deduction was already recorded as cancelled by %s at %s",
                    loanId, loan.getDeductionCancelledBy(), loan.getDeductionCancelledAt()));
        }
        if (loan.getDeductionCancellationStatus() != DeductionCancellationStatus.REQUIRED) {
            throw new ConflictException(String.format("Loan %d has no deduction cancellation pending", loanId));
        }

        String username = authService.getLoggedInUsername();
        String cleanNote = StringUtils.strip(note);
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.CANCELLED_EXTERNALLY);
        loan.setDeductionCancellationNote(cleanNote);
        loan.setDeductionCancelledBy(username);
        loan.setDeductionCancelledAt(LocalDateTime.now());
        Loan saved = loanRepository.save(loan);

        log.info("Deduction for loan {} reference {} recorded as cancelled on Ndasenda's portal by {}",
                loan.getId(), loan.getReference(), username);
        // The note is free text: pinned by its hash, not copied into the audit trail.
        audit(CANCELLED_EXTERNALLY, loan, username, PORTAL_CHANNEL, DeductionCancellationStatus.REQUIRED,
                DeductionCancellationStatus.CANCELLED_EXTERNALLY,
                "reason=" + loan.getDeductionCancellationReason(), AuditService.sha256Hex(cleanNote));
        return DeductionCancellationDto.from(saved);
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
