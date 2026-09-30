package zw.co.innbucks.loans.core;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanBatchService;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.PayslipReviewStatus;
import zw.co.innbucks.loans.core.ndasenda.LoanApprovalRequest;
import zw.co.innbucks.loans.core.ndasenda.LoanApprovalResponse;
import zw.co.innbucks.loans.core.ndasenda.LoanApprovalService;
import zw.co.innbucks.loans.core.ndasenda.LodgementException;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.workflow.CheckpointGate;
import zw.co.innbucks.loans.core.workflow.HoldPoint;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static zw.co.innbucks.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl.maskEcNumber;

/**
 * Lodges NEW SSB loans with Ndasenda: a payroll stop order on a civil servant's salary, which cannot
 * be taken back once it lands. Lodging one twice deducts twice, so every lodgement is:
 * <ol>
 *   <li><b>Claimed</b> — under the loan's row lock, in a transaction COMMITTED before Ndasenda is
 *       called, {@code lodgementClaimedAt} is set on a NEW loan that has none. Another run or another
 *       scheduled-tasks instance finds the claim and leaves the loan alone.</li>
 *   <li><b>Sent once</b> — no transaction is open across the call, and nothing here sends it again
 *       unless it provably never reached Ndasenda.</li>
 *   <li><b>Settled</b> in its own transaction, by what is actually known:
 *     <ul>
 *       <li>accepted: PROCESSING with Ndasenda's batch id, as before;</li>
 *       <li>never reached Ndasenda: back to NEW with the claim released, after a backoff, and FAILED
 *           once {@code maxAttempts} such attempts have failed;</li>
 *       <li>refused by Ndasenda (its own 4xx): FAILED, nothing lodged;</li>
 *       <li>unknown (a timeout, a 5xx, an unreadable answer): held as PROCESSING, which is what the
 *           response job resolves with Ndasenda's own answer. Never FAILED, never sent again.</li>
 *     </ul></li>
 * </ol>
 * One loan's failure never rolls back another's. A run stops at the first sign Ndasenda is
 * unreachable or unwell (a not-sent failure of Ndasenda's making, or an unknown outcome) and lodging
 * pauses for the backoff: every further lodgement then would either burn a loan's attempts or be
 * another deduction in doubt. A NEW loan still claimed after {@code staleClaimAfter} belongs to a run
 * that died mid-lodgement, which may have sent it, so it is held the same way as an unknown outcome.
 */
@Service
@Slf4j
@Profile("scheduled-tasks")
public class NdasendaLodgementJob {

    static final String SYSTEM_ACTOR = "ssb-approval-job";
    static final String LODGEMENT_UNKNOWN = "NDASENDA_LODGEMENT_UNKNOWN";
    static final String LODGEMENT_REFUSED = "NDASENDA_LODGEMENT_REFUSED";
    static final String LODGEMENT_ATTEMPTS_EXHAUSTED = "NDASENDA_LODGEMENT_ATTEMPTS_EXHAUSTED";
    static final String LODGEMENT_NOT_RECORDED = "NDASENDA_LODGEMENT_NOT_RECORDED";

    /** The loan status message column is VARCHAR(255). */
    private static final int MESSAGE_LENGTH = 250;

    // The same texts the Ndasenda path sends. This job used to keep its own
    // copies, which is how a gateway-refused '!' survived here.
    /** What the applicant is told when Ndasenda answers the lodgement (FR-SSB-016). */
    static final Map<LoanApprovalStatus, LoanNotice> NOTICES = Map.of(
            LoanApprovalStatus.PROCESSING, LoanNotice.SENT_TO_SSB,
            LoanApprovalStatus.APPROVED, LoanNotice.SSB_CONFIRMED,
            LoanApprovalStatus.REJECTED, LoanNotice.DECLINED);

    private final LoanApprovalService loanApprovalService;
    private final LoanRepository loanRepository;
    private final LoanNotificationService loanNotificationService;
    private final LoanBatchService loanBatchService;
    private final AuditService auditService;
    private final CheckpointGate checkpointGate;
    private final TransactionTemplate transactionTemplate;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final Duration staleClaimAfter;

    /** Set when a run stops because Ndasenda looks unreachable or unwell; no lodgement starts before it. */
    private volatile LocalDateTime pausedUntil;

    public NdasendaLodgementJob(LoanApprovalService loanApprovalService,
                                  LoanRepository loanRepository,
                                  LoanNotificationService loanNotificationService,
                                  LoanBatchService loanBatchService,
                                  AuditService auditService,
                                  CheckpointGate checkpointGate,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${ndasenda.lodgement.max-attempts:3}") int maxAttempts,
                                  @Value("${ndasenda.lodgement.retry-backoff-minutes:10}") long retryBackoffMinutes,
                                  @Value("${ndasenda.lodgement.stale-claim-minutes:30}") long staleClaimMinutes) {
        if (maxAttempts < 1 || retryBackoffMinutes < 1 || staleClaimMinutes < 1) {
            throw new IllegalArgumentException("ndasenda.lodgement.max-attempts, retry-backoff-minutes and"
                    + " stale-claim-minutes must all be at least 1");
        }
        this.loanApprovalService = loanApprovalService;
        this.loanRepository = loanRepository;
        this.loanNotificationService = loanNotificationService;
        this.loanBatchService = loanBatchService;
        this.auditService = auditService;
        this.checkpointGate = checkpointGate;
        this.maxAttempts = maxAttempts;
        this.retryBackoff = Duration.ofMinutes(retryBackoffMinutes);
        this.staleClaimAfter = Duration.ofMinutes(staleClaimMinutes);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Always a fresh transaction: the claim must be COMMITTED before Ndasenda is called, and each
        // loan's outcome must commit or roll back on its own.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedRate = 60000) // Run every 1 minute (60,000 milliseconds)
    public void processSsbApprovals() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        holdAbandonedClaims(now);

        LocalDateTime paused = pausedUntil;
        if (paused != null && now.isBefore(paused)) {
            log.info("SSB lodgement paused until {}: Ndasenda was unreachable or answered unclearly", paused);
            return;
        }

        // Held at a checkpoint (FR-SSB-014): not lodged until it is cleared.
        List<Long> due = checkpointGate.withoutHeld(HoldPoint.BEFORE_LODGEMENT,
                loanRepository.findIdsDueForLodgement(now));
        log.info("SSB lodgement run: {} loan(s) due", due.size());
        for (int i = 0; i < due.size(); i++) {
            if (!lodge(due.get(i))) {
                pausedUntil = LocalDateTime.now(ZoneOffset.UTC).plus(retryBackoff);
                log.warn("SSB lodgement run stopped at loan {}; {} loan(s) left untouched, lodging paused until {}",
                        due.get(i), due.size() - i - 1, pausedUntil);
                return;
            }
        }
    }

    /** @return whether the run may carry on to the next loan */
    private boolean lodge(Long loanId) {
        final Claim claim;
        try {
            claim = transactionTemplate.execute(status -> claim(loanId));
        } catch (RuntimeException ex) {
            // The claim did not commit, so nothing was sent.
            log.error("Could not claim loan {} for lodgement; it was not sent", loanId, ex);
            return true;
        }
        if (claim == null) {
            return true;
        }

        LoanApprovalResponse response = null;
        LodgementException failure;
        try {
            response = loanApprovalService.requestApproval(claim.request());
            failure = response == null || response.getStatus() == null
                    ? new LodgementException(LodgementException.Kind.OUTCOME_UNKNOWN, "The lodgement returned no outcome", null)
                    : null;
        } catch (LodgementException ex) {
            failure = ex;
        } catch (RuntimeException ex) {
            // Nobody classified it, so it may have been thrown after the request left.
            failure = new LodgementException(LodgementException.Kind.OUTCOME_UNKNOWN,
                    "Unclassified lodgement failure: " + LodgementException.describe(ex), ex);
        }

        final LoanApprovalResponse accepted = failure == null ? response : null;
        final LodgementException refusal = failure;
        final Settled settled;
        try {
            settled = transactionTemplate.execute(status -> settle(claim, accepted, refusal));
        } catch (RuntimeException ex) {
            notRecorded(claim, accepted, refusal, ex);
            return carriesOn(refusal);
        }
        afterCommit(claim, settled);
        return settled.carryOn();
    }

    private Claim claim(Long loanId) {
        Loan loan = loanRepository.findByIdForUpdate(loanId).orElse(null);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // Re-checked under the row lock: another run or instance may have claimed or settled it since
        // the due list was read.
        if (loan == null || loan.getLoanApprovalStatus() != LoanApprovalStatus.NEW
                || heldForPayslipReview(loan)
                || loan.getInternalApprovalStatus() == InternalApprovalStatus.REJECTED
                || loanRepository.isHeldForEmploymentEvent(loanId)
                || checkpointGate.holding(HoldPoint.BEFORE_LODGEMENT, loan).isPresent()
                || loan.getLodgementClaimedAt() != null
                || (loan.getNextLodgementAttemptAt() != null && loan.getNextLodgementAttemptAt().isAfter(now))) {
            log.info("Loan {} is no longer due for lodgement (claimed, settled, deferred, held or declined since the run"
                            + " began); skipped",
                    loanId);
            return null;
        }
        // Millisecond precision survives the round trip to the column exactly, so the settle can
        // recognise its own claim.
        LocalDateTime claimedAt = now.truncatedTo(ChronoUnit.MILLIS);
        loan.setLodgementClaimedAt(claimedAt);
        loanRepository.save(loan);

        LoanApprovalRequest request = LoanApprovalRequest.builder()
                .monthlyInstallment(loan.getGrossedMonthlyDeduction())
                .ecnumber(loan.getEcNumber())
                .idNumber(loan.getNationalIdNumber())
                .reference(loan.getReference())
                .tenor(loan.getTenor())
                .build();
        log.info("Loan {} claimed for lodgement with Ndasenda at {}", loanId, claimedAt);
        return new Claim(loan.getId(), claimedAt, loan.getReference(), request);
    }

    /**
     * Waiting for a payslip review, or rejected by one (FR-SSB-007): never lodged. The due query already
     * leaves these out; this re-checks under the lock, since a review can land between the two.
     */
    private static boolean heldForPayslipReview(Loan loan) {
        return loan.getPayslipReviewStatus() == PayslipReviewStatus.PENDING
                || loan.getPayslipReviewStatus() == PayslipReviewStatus.CONFIRMED;
    }

    private Settled settle(Claim claim, LoanApprovalResponse accepted, LodgementException failure) {
        Loan loan = loanRepository.findByIdForUpdate(claim.loanId())
                .orElseThrow(() -> new IllegalStateException("Loan " + claim.loanId() + " vanished mid-lodgement"));
        if (!holdsClaim(loan, claim)) {
            return claimSuperseded(loan, claim, accepted, failure);
        }
        if (failure == null) {
            return accepted(loan, accepted);
        }
        return switch (failure.getKind()) {
            case NOT_SENT, NDASENDA_UNAVAILABLE -> notSent(loan, failure);
            case REFUSED -> refused(loan, failure);
            case OUTCOME_UNKNOWN -> {
                hold(loan, "Ndasenda lodgement outcome unknown; held for Ndasenda's answer, not sent again. "
                        + failure.getMessage());
                yield new Settled(false, null);
            }
        };
    }

    /**
     * Still this lodgement's to settle: it carries our claim and is waiting on it, either NEW or
     * PROCESSING with no answer from Ndasenda yet (held by {@link #holdAbandonedClaims} while this
     * call was still running).
     */
    private static boolean holdsClaim(Loan loan, Claim claim) {
        if (!claim.claimedAt().equals(loan.getLodgementClaimedAt())) {
            return false;
        }
        return loan.getLoanApprovalStatus() == LoanApprovalStatus.NEW
                || (loan.getLoanApprovalStatus() == LoanApprovalStatus.PROCESSING
                && !DeductionCancellationService.wasLodged(loan));
    }

    private Settled accepted(Loan loan, LoanApprovalResponse response) {
        log.info(">> Updating loan status: {}", response);
        loan.setLoanApprovalStatus(response.getStatus());
        loan.setApprovalReference(response.getReference());
        loan.setBatchNumber(response.getBatchNumber());
        loan.setDateApproved(LocalDateTime.now(ZoneOffset.UTC));
        loan.setRepaymentStartDate(response.getStartDate());
        loan.setRepaymentEndDate(response.getEndDate());
        loan.setNextLodgementAttemptAt(null);
        // Any note from an earlier attempt that never reached Ndasenda no longer applies.
        loan.setLoanStatusMessage(null);
        loanRepository.save(loan);

        LoanNotice notice = NOTICES.get(response.getStatus());
        if (notice != null) {
            // Sent once this settle commits.
            loanNotificationService.notify(loan, notice);
        }
        return new Settled(true, response.getBatchNumber());
    }

    /** Nothing reached Ndasenda: released for another attempt after a backoff, or FAILED once they are spent. */
    private Settled notSent(Loan loan, LodgementException failure) {
        LoanApprovalStatus from = loan.getLoanApprovalStatus();
        int attempts = (loan.getApprovalAttempt() == null ? 0 : loan.getApprovalAttempt()) + 1;
        loan.setApprovalAttempt(attempts);
        loan.setLodgementClaimedAt(null);

        if (attempts >= maxAttempts) {
            loan.setLoanApprovalStatus(LoanApprovalStatus.FAILED);
            loan.setNextLodgementAttemptAt(null);
            loan.setLoanStatusMessage(StringUtils.left("Not lodged: " + attempts + " attempt(s) never reached Ndasenda. Last: "
                    + failure.getMessage(), MESSAGE_LENGTH));
            log.error("NDASENDA LODGEMENT GIVEN UP: loan {} reference {} ec {} - {} attempt(s), none of which reached"
                            + " Ndasenda; last: {}. Nothing was lodged (audited)",
                    loan.getId(), loan.getReference(), maskEcNumber(loan.getEcNumber()), attempts, failure.getMessage());
            audit(LODGEMENT_ATTEMPTS_EXHAUSTED, loan, from, LoanApprovalStatus.FAILED,
                    "reason=" + failure.getKind() + " " + failure.getMessage());
            // The application goes no further, so the applicant is told (FR-SSB-016).
            loanNotificationService.notify(loan, LoanNotice.NOT_COMPLETED);
        } else {
            LocalDateTime next = LocalDateTime.now(ZoneOffset.UTC).plus(backoff(attempts));
            loan.setLoanApprovalStatus(LoanApprovalStatus.NEW);
            loan.setNextLodgementAttemptAt(next);
            loan.setLoanStatusMessage(StringUtils.left("Lodgement attempt " + attempts + " of " + maxAttempts
                    + " never reached Ndasenda; next after " + next.truncatedTo(ChronoUnit.SECONDS) + ". "
                    + failure.getMessage(), MESSAGE_LENGTH));
            log.warn("Loan {} reference {} was not lodged (attempt {} of {}, nothing reached Ndasenda): {}; next attempt after {}",
                    loan.getId(), loan.getReference(), attempts, maxAttempts, failure.getMessage(), next);
        }
        loanRepository.save(loan);
        return new Settled(failure.getKind() == LodgementException.Kind.NOT_SENT, null);
    }

    /** Ndasenda's own refusal: nothing was lodged, and sending it again would be refused the same way. */
    private Settled refused(Loan loan, LodgementException failure) {
        LoanApprovalStatus from = loan.getLoanApprovalStatus();
        loan.setApprovalAttempt(loan.getApprovalAttempt() == null ? 1 : loan.getApprovalAttempt() + 1);
        loan.setLoanApprovalStatus(LoanApprovalStatus.FAILED);
        loan.setNextLodgementAttemptAt(null);
        loan.setLoanStatusMessage(StringUtils.left(failure.getMessage(), MESSAGE_LENGTH));
        loanRepository.save(loan);
        log.error("NDASENDA LODGEMENT REFUSED: loan {} reference {} ec {} - {}. Nothing was lodged (audited)",
                loan.getId(), loan.getReference(), maskEcNumber(loan.getEcNumber()), failure.getMessage());
        audit(LODGEMENT_REFUSED, loan, from, LoanApprovalStatus.FAILED, "reason=" + failure.getMessage());
        loanNotificationService.notify(loan, LoanNotice.NOT_COMPLETED);
        return new Settled(true, null);
    }

    /**
     * The deduction may be live at Ndasenda, so the loan waits for Ndasenda's own answer: PROCESSING is
     * what the response job resolves, and it needs no batch id to do so (it matches on the reference).
     * The claim stays, so the loan is never sent again. Only mutates and saves the loan.
     */
    private void hold(Loan loan, String why) {
        LoanApprovalStatus from = loan.getLoanApprovalStatus();
        loan.setLoanApprovalStatus(LoanApprovalStatus.PROCESSING);
        loan.setNextLodgementAttemptAt(null);
        loan.setLoanStatusMessage(StringUtils.left(why, MESSAGE_LENGTH));
        loanRepository.save(loan);
        log.error("NDASENDA LODGEMENT OUTCOME UNKNOWN: loan {} reference {} ec {} instalment {} claimed {} - {}."
                        + " It may be live at Ndasenda, so it is held as PROCESSING for Ndasenda's answer and will"
                        + " not be sent again. If Ndasenda confirms it never arrived, set the loan back to NEW and"
                        + " clear its lodgement claim (audited)",
                loan.getId(), loan.getReference(), maskEcNumber(loan.getEcNumber()),
                loan.getGrossedMonthlyDeduction(), loan.getLodgementClaimedAt(), why);
        audit(LODGEMENT_UNKNOWN, loan, from, LoanApprovalStatus.PROCESSING, "reason=" + why);
    }

    /** The loan moved on under us (an operator, or Ndasenda's answer after the claim was held): recorded, not applied. */
    private Settled claimSuperseded(Loan loan, Claim claim, LoanApprovalResponse accepted, LodgementException failure) {
        String outcome = outcome(accepted, failure);
        log.error("NDASENDA LODGEMENT NOT RECORDED: loan {} reference {} is no longer waiting on the lodgement claimed at {}"
                        + " (status {}, claim {}); its outcome was {} and is not applied (audited)",
                loan.getId(), loan.getReference(), claim.claimedAt(), loan.getLoanApprovalStatus(),
                loan.getLodgementClaimedAt(), outcome);
        audit(LODGEMENT_NOT_RECORDED, loan, loan.getLoanApprovalStatus(), loan.getLoanApprovalStatus(),
                "reason=claim_superseded claimedAt=" + claim.claimedAt() + " outcome=" + outcome);
        return new Settled(carriesOn(failure), null);
    }

    /**
     * The outcome could not be saved. The claim committed and still stands, so the loan is never sent
     * again; {@link #holdAbandonedClaims} holds it for Ndasenda's answer once the claim goes stale.
     */
    private void notRecorded(Claim claim, LoanApprovalResponse accepted, LodgementException failure, RuntimeException ex) {
        String outcome = outcome(accepted, failure);
        log.error("NDASENDA LODGEMENT NOT RECORDED: loan {} reference {} claimed at {} - outcome {} could not be saved."
                        + " The claim stands, so it will not be sent again; it is held for Ndasenda's answer after {} (audited)",
                claim.loanId(), claim.reference(), claim.claimedAt(), outcome, staleClaimAfter, ex);
        try {
            auditService.record(AuditLog.builder()
                    .eventType(LODGEMENT_NOT_RECORDED)
                    .entityType("LOAN").entityId(String.valueOf(claim.loanId()))
                    .actorId(SYSTEM_ACTOR).channelUsed("system")
                    .detail("reason=settle_failed claimedAt=" + claim.claimedAt() + " outcome=" + outcome
                            + " error=" + LodgementException.describe(ex) + " reference=" + claim.reference())
                    .correlationId(claim.reference()));
        } catch (Exception auditFailed) {
            log.error("Audit of loan {} ({}) failed", claim.loanId(), LODGEMENT_NOT_RECORDED, auditFailed);
        }
    }

    /**
     * A NEW loan still claimed after {@link #staleClaimAfter} belongs to a run that stopped between
     * the claim and its settle (a restart, a crash, a failed save). Its lodgement may have been sent,
     * so it is held for Ndasenda's answer like any unknown outcome. Each loan in its own transaction.
     */
    private void holdAbandonedClaims(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(staleClaimAfter);
        List<Long> abandoned;
        try {
            abandoned = loanRepository.findIdsWithLodgementClaimedBefore(cutoff);
        } catch (RuntimeException ex) {
            log.error("Could not look for abandoned lodgement claims", ex);
            return;
        }
        for (Long loanId : abandoned) {
            try {
                transactionTemplate.executeWithoutResult(status -> loanRepository.findByIdForUpdate(loanId)
                        .filter(loan -> loan.getLoanApprovalStatus() == LoanApprovalStatus.NEW
                                && loan.getLodgementClaimedAt() != null && loan.getLodgementClaimedAt().isBefore(cutoff))
                        .ifPresent(loan -> hold(loan, "A lodgement started " + loan.getLodgementClaimedAt()
                                + " was never recorded, so it may have reached Ndasenda; held for its answer, not sent again")));
            } catch (RuntimeException ex) {
                log.error("Could not hold loan {}, whose lodgement claim is stale; it stays claimed and unsent", loanId, ex);
            }
        }
    }

    /** Best-effort, after the loan is committed: the batch listing may not undo a lodgement. */
    private void afterCommit(Claim claim, Settled settled) {
        if (StringUtils.isNotBlank(settled.batchNumber())) {
            try {
                log.info("saving loan batch: {}", settled.batchNumber());
                loanBatchService.save(settled.batchNumber());
            } catch (RuntimeException ex) {
                log.error("Loan {} is lodged in Ndasenda batch {} but the batch could not be listed;"
                        + " the loan itself records it", claim.loanId(), settled.batchNumber(), ex);
            }
        }
    }

    private Duration backoff(int attempts) {
        return retryBackoff.multipliedBy(1L << Math.min(attempts - 1, 10));
    }

    /** A run carries on past a loan unless Ndasenda looked unreachable or answered unclearly. */
    private static boolean carriesOn(LodgementException failure) {
        return failure == null || failure.getKind() == LodgementException.Kind.NOT_SENT
                || failure.getKind() == LodgementException.Kind.REFUSED;
    }

    private static String outcome(LoanApprovalResponse accepted, LodgementException failure) {
        return failure == null
                ? "ACCEPTED status=" + accepted.getStatus() + " batch=" + accepted.getBatchNumber()
                : failure.getKind() + " " + failure.getMessage();
    }

    private void audit(String eventType, Loan loan, LoanApprovalStatus from, LoanApprovalStatus to, String outcome) {
        try {
            // Identifiers only, as for the other NDASENDA_* events: the national ID is never copied here
            // and the EC number keeps its last 3 characters.
            auditService.record(AuditLog.builder()
                    .eventType(eventType)
                    .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                    .actorId(SYSTEM_ACTOR).channelUsed("system")
                    .stateTransitionDelta("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}")
                    .detail(outcome + " reference=" + loan.getReference() + " attempts=" + loan.getApprovalAttempt()
                            + " claimedAt=" + loan.getLodgementClaimedAt() + " batch=" + loan.getBatchNumber()
                            + " instalment=" + loan.getGrossedMonthlyDeduction()
                            + " ecNumber=" + maskEcNumber(loan.getEcNumber()))
                    .correlationId(loan.getReference()));
        } catch (Exception ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while
            // opening the transaction; the ERROR above is the evidence, and the loan must still settle.
            log.error("Audit of loan {} ({}) failed", loan.getId(), eventType, ex);
        }
    }

    /** What one lodgement needs after its claim commits; {@code claimedAt} is how its settle knows the loan is still its own. */
    private record Claim(Long loanId, LocalDateTime claimedAt, String reference, LoanApprovalRequest request) {
    }

    private record Settled(boolean carryOn, String batchNumber) {
    }
}
