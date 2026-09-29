package zw.co.reikan.loans.core.disbursements;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanDisbursementRepository;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.loan.PayoutDestination;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static zw.co.reikan.loans.core.loan.LoanApprovalStatus.APPROVED;

/**
 * Books credit-approved loans with InnBucks, which books AND pays on the one call, so booking a loan
 * twice pays it twice. Every booking is therefore:
 * <ol>
 *   <li><b>Claimed</b> — under the loan's row lock, in a transaction COMMITTED before InnBucks is
 *       called, {@code bookingClaimedAt} is set on an account-PENDING loan that has none. Another run
 *       or another scheduled-tasks instance finds the claim and leaves the loan alone.</li>
 *   <li><b>Sent once</b> — no transaction is open across the call.</li>
 *   <li><b>Settled</b> in its own transaction, and only while the loan still carries this run's
 *       claim: booked (CREATED/PENDING for the inquiry job), refused (FAILED, deduction flagged),
 *       outcome unknown (held CREATED/PENDING as AMBIGUOUS, never booked again), or never sent (claim
 *       released, booked on a later run).</li>
 * </ol>
 * A run stops at the first booking that never reached InnBucks or whose outcome is unknown: every
 * loan behind it would most likely end the same way, and each unknown outcome is a loan held for an
 * operator. An account-PENDING loan still claimed after {@code staleClaimAfter} belongs to a run that
 * died mid-booking, which may have sent it, so it is held the same way as an unknown outcome.
 */
@Service
@Slf4j
@Profile("scheduled-tasks")
public class LoanAccountCreationJob {

    static final String SYSTEM_ACTOR = "loan-account-creation-job";
    static final String BOOKING_NOT_RECORDED = "INNBUCKS_BOOKING_NOT_RECORDED";
    static final String BOOKING_CLAIM_ABANDONED = "INNBUCKS_BOOKING_CLAIM_ABANDONED";

    private final DisbursementService disbursementService;
    private final LoanRepository loanRepository;
    private final DeductionCancellationService deductionCancellationService;
    private final LoanDisbursementRepository loanDisbursementRepository;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final Duration staleClaimAfter;

    public LoanAccountCreationJob(DisbursementService disbursementService,
                                  LoanRepository loanRepository,
                                  DeductionCancellationService deductionCancellationService,
                                  LoanDisbursementRepository loanDisbursementRepository,
                                  AuditService auditService,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${innbucks.booking.stale-claim-minutes:30}") long staleClaimMinutes) {
        if (staleClaimMinutes < 1) {
            throw new IllegalArgumentException("innbucks.booking.stale-claim-minutes must be at least 1");
        }
        this.disbursementService = disbursementService;
        this.loanRepository = loanRepository;
        this.deductionCancellationService = deductionCancellationService;
        this.loanDisbursementRepository = loanDisbursementRepository;
        this.auditService = auditService;
        this.staleClaimAfter = Duration.ofMinutes(staleClaimMinutes);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Always a fresh transaction: the claim must be COMMITTED before InnBucks is called, and each
        // loan's outcome must commit or roll back on its own.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs every 2 minutes. */
    @Scheduled(fixedRate = 120_000)
    public void processLoanAccountCreation() {
        log.info("Starting LoanAccountCreationJob...");
        holdAbandonedClaims(LocalDateTime.now());

        List<Long> due = loanRepository.findIdsDueForBooking();
        for (int i = 0; i < due.size(); i++) {
            if (!book(due.get(i))) {
                log.warn("InnBucks booking run stopped at loan {}; {} loan(s) left for the next run",
                        due.get(i), due.size() - i - 1);
                return;
            }
        }
    }

    /** @return whether the run may carry on to the next loan */
    private boolean book(Long loanId) {
        final Claim claim;
        try {
            claim = transactionTemplate.execute(status -> claim(loanId));
        } catch (RuntimeException ex) {
            // The claim did not commit, so nothing was sent.
            log.error("Could not claim loan {} for booking; it was not sent", loanId, ex);
            return true;
        }
        if (claim == null) {
            return true;
        }

        LoanAccountCreationResponse response = null;
        Exception failure = null;
        try {
            response = disbursementService.createLoanAccount(claim.loan());
            if (response == null) {
                // Nobody classified it, so it may have come back after the request left.
                failure = new IllegalStateException("The InnBucks booking returned no outcome");
            }
        } catch (Exception ex) {
            failure = ex;
        }

        final Outcome outcome = new Outcome(response, failure);
        try {
            transactionTemplate.executeWithoutResult(status -> settle(claim, outcome));
        } catch (RuntimeException ex) {
            notRecorded(claim, outcome, ex);
        }
        return outcome.carriesOn();
    }

    private Claim claim(Long loanId) {
        Loan loan = loanRepository.findByIdForUpdate(loanId).orElse(null);
        // Re-checked under the row lock: another run or instance may have claimed or settled it since
        // the due list was read.
        if (loan == null || loan.getLoanApprovalStatus() != APPROVED
                || loan.getInternalApprovalStatus() != InternalApprovalStatus.APPROVED
                || loan.getLoanAccountStatus() != LoanAccountStatus.PENDING
                || loan.getBookingClaimedAt() != null) {
            log.info("Loan {} is no longer due for booking (claimed or settled since the run began); skipped", loanId);
            return null;
        }
        if (mustNotBook(loan)) {
            return null;
        }
        // Millisecond precision survives the round trip to the column exactly, so the settle can
        // recognise its own claim.
        LocalDateTime claimedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        loan.setBookingClaimedAt(claimedAt);
        loanRepository.save(loan);
        log.info("Loan {} claimed for booking with InnBucks at {}", loanId, claimedAt);
        return new Claim(loanId, claimedAt, loan);
    }

    /**
     * InnBucks pays on the booking, so booking a loan that is already paid, or whose payout
     * is in flight, pays it again. Defence in depth: only account-PENDING loans are due, but other
     * code (an SSB re-approval) can put a loan back to PENDING.
     */
    private boolean mustNotBook(Loan loan) {
        if (loan.getDisbursementStatus() == LoanDisbursementStatus.SUCCESS) {
            log.warn("Loan {} is already disbursed; not booking it again", loan.getId());
            return true;
        }
        if (loan.getBookingFailureKind() == BookingFailureKind.AMBIGUOUS) {
            log.warn("Loan {}: an earlier InnBucks booking has an unknown outcome and may have paid it;"
                    + " held for an operator, not re-booked", loan.getId());
            return true;
        }
        if (loanDisbursementRepository.existsByLoanId(loan.getId())) {
            log.warn("Loan {} has a manual payout attempt; not booking it", loan.getId());
            return true;
        }
        return false;
    }

    private void settle(Claim claim, Outcome outcome) {
        Loan loan = loanRepository.findByIdForUpdate(claim.loanId())
                .orElseThrow(() -> new IllegalStateException("Loan " + claim.loanId() + " vanished mid-booking"));
        if (!holdsClaim(loan, claim)) {
            claimSuperseded(loan, claim, outcome);
            return;
        }

        if (outcome.failure() instanceof BookingNotSentException notSent) {
            // Nothing reached InnBucks (its login failed, or the connection never opened), so the
            // claim is released and the loan is booked on a later run. Recording it as a refusal
            // would have failed every queued loan on one login outage; recording it as an unknown
            // outcome would have held each one for good.
            log.error("{}; loan {} stays PENDING and this run stops until InnBucks is reachable",
                    notSent.getMessage(), loan.getId(), notSent);
            loan.setBookingClaimedAt(null);
            loan.setDisbursementStatusMessage(truncate(notSent.getMessage() + " - will retry"));
        } else if (outcome.failure() != null) {
            handleAccountCreationException(loan, outcome.failure());
        } else if (outcome.response().isSuccess()) {
            handleSuccessfulAccountCreation(loan, outcome.response());
        } else {
            handleFailedAccountCreation(loan, outcome.response());
        }
        loanRepository.save(loan);
    }

    /**
     * Still this booking's to settle: it carries our claim and is waiting on it, either account
     * PENDING or held AMBIGUOUS by {@link #holdAbandonedClaims} while this call was still running
     * (an answer that has since arrived is better evidence than the hold).
     */
    private static boolean holdsClaim(Loan loan, Claim claim) {
        if (!claim.claimedAt().equals(loan.getBookingClaimedAt())) {
            return false;
        }
        return loan.getLoanAccountStatus() == LoanAccountStatus.PENDING
                || (loan.getLoanAccountStatus() == LoanAccountStatus.CREATED
                && loan.getDisbursementStatus() == LoanDisbursementStatus.PENDING
                && loan.getBookingFailureKind() == BookingFailureKind.AMBIGUOUS);
    }

    private void handleSuccessfulAccountCreation(Loan loan, LoanAccountCreationResponse response) {
        log.info("Loan account created successfully for loan: {}", loan.getId());

        loan.setBookingFailureKind(null);
        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        loan.setDisbursementStatus(LoanDisbursementStatus.PENDING);
        loan.setDisbursementReference(response.getReference());
        // Any note from an earlier attempt that never reached InnBucks no longer applies.
        loan.setDisbursementStatusMessage(null);
        // Don't set date disbursed yet as the disbursement is pending
        // The merchant account the booking actually paid (none for a customer-wallet loan), as the
        // manual payout records it.
        loan.setDisbursementMerchantAccountNumber(PayoutDestination.of(loan).merchantAccount());

        // Don't notify customer yet as the disbursement is pending
    }

    /** A non-2xx InnBucks answered and we read: a definite refusal. */
    private void handleFailedAccountCreation(Loan loan, LoanAccountCreationResponse response) {
        log.info("Loan account creation failed for loan: {}", loan.getId());

        loan.setBookingFailureKind(BookingFailureKind.REFUSED);
        loan.setLoanAccountStatus(LoanAccountStatus.FAILED);
        loan.setDisbursementStatus(LoanDisbursementStatus.FAILED);
        loan.setDisbursementReference(response.getReference());
        loan.setDisbursementStatusMessage(truncate("InnBucks loan application failed: "
                + (response.getMessage() == null ? "unsuccessful response" : response.getMessage())));
        // InnBucks answered and refused, so no loan was booked; the deduction Ndasenda accepted is live.
        flagDeduction(loan, DeductionCancellationService.REASON_BOOKING_FAILED);
    }

    private void handleAccountCreationException(Loan loan, Exception ex) {
        BookingFailureKind kind = classify(ex);
        loan.setBookingFailureKind(kind);

        if (kind == BookingFailureKind.REFUSED) {
            log.error("Loan account creation refused for loan: {}", loan.getId(), ex);
            loan.setLoanAccountStatus(LoanAccountStatus.FAILED);
            loan.setDisbursementStatus(LoanDisbursementStatus.FAILED);
            loan.setDisbursementStatusMessage(truncate("InnBucks loan application failed: " + describe(ex)));
            // InnBucks said no, so nothing was booked; the deduction Ndasenda accepted is live.
            flagDeduction(loan, DeductionCancellationService.REASON_BOOKING_FAILED);
            return;
        }

        // The booking may have landed, and InnBucks pays on booking. Marking it FAILED would
        // read as "definitively not paid" and invite a recovery payout. Treat it as booked
        // instead: the inquiry job resolves a booking that landed, and one that did not
        // simply stays PENDING for an operator — never re-booked, never re-paid.
        // Nothing is flagged for cancellation either: the customer may hold this loan. If the
        // inquiry reports it missing, an operator confirms with InnBucks and resolves it through
        // POST /api/loans/{id}/booking/confirm-not-booked, which fails it and flags the deduction.
        log.error("Loan account creation outcome unknown for loan: {}; holding it for the inquiry job",
                loan.getId(), ex);
        hold(loan, "InnBucks loan application outcome unknown (held, not failed): " + describe(ex));
    }

    /** CREATED/PENDING is what the inquiry job polls: a booking that landed resolves there. */
    private static void hold(Loan loan, String why) {
        loan.setBookingFailureKind(BookingFailureKind.AMBIGUOUS);
        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        loan.setDisbursementStatus(LoanDisbursementStatus.PENDING);
        loan.setDisbursementReference(loan.getReference());
        loan.setDisbursementStatusMessage(truncate(why));
    }

    /** The loan moved on under us (an operator, or the inquiry job): recorded, not applied. */
    private void claimSuperseded(Loan loan, Claim claim, Outcome outcome) {
        log.error("INNBUCKS BOOKING NOT RECORDED: loan {} reference {} is no longer waiting on the booking claimed at {}"
                        + " (account {}, disbursement {}, claim {}); its outcome was {} and is not applied (audited)",
                loan.getId(), loan.getReference(), claim.claimedAt(), loan.getLoanAccountStatus(),
                loan.getDisbursementStatus(), loan.getBookingClaimedAt(), outcome.describe());
        audit(BOOKING_NOT_RECORDED, claim.loanId(), loan.getReference(),
                "reason=claim_superseded claimedAt=" + claim.claimedAt() + " outcome=" + outcome.describe());
    }

    /**
     * The outcome could not be saved. The claim committed and still stands, so the loan is never
     * booked again; {@link #holdAbandonedClaims} holds it for the inquiry job once the claim goes stale.
     */
    private void notRecorded(Claim claim, Outcome outcome, RuntimeException ex) {
        log.error("INNBUCKS BOOKING NOT RECORDED: loan {} claimed at {} - outcome {} could not be saved."
                        + " The claim stands, so it will not be booked again; it is held for the inquiry job after {}"
                        + " (audited)",
                claim.loanId(), claim.claimedAt(), outcome.describe(), staleClaimAfter, ex);
        audit(BOOKING_NOT_RECORDED, claim.loanId(), claim.loan().getReference(),
                "reason=settle_failed claimedAt=" + claim.claimedAt() + " outcome=" + outcome.describe()
                        + " error=" + ex.getClass().getSimpleName());
    }

    /**
     * An account-PENDING loan still claimed after {@link #staleClaimAfter} belongs to a run that stopped
     * between the claim and its settle (a restart, a crash, a failed save). Its booking may have been
     * sent, so it is held for the inquiry job like any unknown outcome. Each loan in its own transaction.
     */
    private void holdAbandonedClaims(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(staleClaimAfter);
        List<Long> abandoned;
        try {
            abandoned = loanRepository.findIdsWithBookingClaimedBefore(cutoff);
        } catch (RuntimeException ex) {
            log.error("Could not look for abandoned booking claims", ex);
            return;
        }
        for (Long loanId : abandoned) {
            try {
                transactionTemplate.executeWithoutResult(status -> loanRepository.findByIdForUpdate(loanId)
                        .filter(loan -> loan.getLoanAccountStatus() == LoanAccountStatus.PENDING
                                && loan.getBookingClaimedAt() != null && loan.getBookingClaimedAt().isBefore(cutoff))
                        .ifPresent(this::holdAbandoned));
            } catch (RuntimeException ex) {
                log.error("Could not hold loan {}, whose booking claim is stale; it stays claimed and unbooked",
                        loanId, ex);
            }
        }
    }

    private void holdAbandoned(Loan loan) {
        LocalDateTime claimedAt = loan.getBookingClaimedAt();
        hold(loan, "An InnBucks booking started " + claimedAt
                + " was never recorded, so it may have landed; held for the inquiry job, not booked again");
        loanRepository.save(loan);
        log.error("INNBUCKS BOOKING CLAIM ABANDONED: loan {} reference {} was claimed for booking at {} and never"
                        + " settled. It may be booked and paid at InnBucks, so it is held for the inquiry job and"
                        + " will not be booked again (audited)",
                loan.getId(), loan.getReference(), claimedAt);
        audit(BOOKING_CLAIM_ABANDONED, loan.getId(), loan.getReference(), "claimedAt=" + claimedAt);
    }

    /**
     * Only InnBucks' own 4xx answer proves it did not book. A 5xx, a timeout, a reset after
     * the request left, a parse error — anything that is not a 4xx — may follow a booking
     * that landed. So may a 409 (plausibly "this participantReference already exists") and
     * a 408 (it stopped reading, which is not a statement of what it did). A 401 reaching
     * here is the answer to our one re-authenticated replay, so it counts as a refusal.
     */
    static BookingFailureKind classify(Exception ex) {
        if (ex instanceof HttpClientErrorException http) {
            int status = http.getStatusCode().value();
            return status == 408 || status == 409 ? BookingFailureKind.AMBIGUOUS : BookingFailureKind.REFUSED;
        }
        return BookingFailureKind.AMBIGUOUS;
    }

    /**
     * Flagged here, where the reason is known, rather than only when the saga compensates: the
     * saga never sees a loan whose saga is already terminal (an SSB_VERIFICATION_FAILED lodgement
     * that Ndasenda later accepted). Every loan here was accepted by Ndasenda, so its deduction is
     * live. Saved with the loan by the caller.
     */
    private void flagDeduction(Loan loan, String reason) {
        deductionCancellationService.markRequired(loan, reason, SYSTEM_ACTOR, "system");
    }

    private void audit(String eventType, Long loanId, String reference, String detail) {
        try {
            auditService.record(AuditLog.builder()
                    .eventType(eventType)
                    .entityType("LOAN").entityId(String.valueOf(loanId))
                    .actorId(SYSTEM_ACTOR).channelUsed("system")
                    .detail(detail + " reference=" + reference)
                    .correlationId(reference));
        } catch (Exception ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while
            // opening the transaction; the ERROR above is the evidence, and the run must carry on.
            log.error("Audit of loan {} ({}) failed", loanId, eventType, ex);
        }
    }

    /**
     * The reason an operator (and the portal) can act on. For an HTTP refusal
     * that is InnBucks' own status + body — its error text is the most useful
     * thing we hold; for anything else, the exception's message. Before this,
     * a failed loan read only {@code disbursementStatus: FAILED}, with nothing
     * to say whether to fix the data, retry, or call InnBucks.
     */
    private static String describe(Exception ex) {
        if (ex instanceof RestClientResponseException http) {
            String body = http.getResponseBodyAsString();
            return "HTTP " + http.getStatusCode().value()
                    + (body == null || body.isBlank() ? "" : " " + body.strip());
        }
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /** disbursement_status_message is a VARCHAR(255). */
    private static String truncate(String message) {
        return message.length() <= 255 ? message : message.substring(0, 252) + "...";
    }

    /** What one booking needs after its claim commits; {@code claimedAt} is how its settle knows the loan is still its own. */
    private record Claim(Long loanId, LocalDateTime claimedAt, Loan loan) {
    }

    /** What InnBucks answered, or how the call failed. */
    private record Outcome(LoanAccountCreationResponse response, Exception failure) {

        /** A run carries on past a loan unless InnBucks looked unreachable or answered unclearly. */
        boolean carriesOn() {
            if (failure == null) {
                return true;
            }
            return !(failure instanceof BookingNotSentException) && classify(failure) == BookingFailureKind.REFUSED;
        }

        String describe() {
            if (failure instanceof BookingNotSentException) {
                return "NOT_SENT";
            }
            if (failure != null) {
                return classify(failure) + " " + failure.getClass().getSimpleName();
            }
            return response.isSuccess() ? "BOOKED" : "REFUSED " + response.getMessage();
        }
    }
}
