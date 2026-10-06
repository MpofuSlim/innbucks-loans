package zw.co.innbucks.loans.core;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.ManualDisbursementResponse.Outcome;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.disbursements.BookingFailureKind;
import zw.co.innbucks.loans.core.disbursements.LoanAccountCreationResponse;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatusResponse;
import zw.co.innbucks.loans.core.exception.DisbursementNotAllowedException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.ledger.DisbursementLedger;
import zw.co.innbucks.loans.core.loan.*;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.workflow.CheckpointGate;
import zw.co.innbucks.loans.core.workflow.HoldPoint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Slf4j
public abstract class DisbursementService {

    /** Render through {@link #walletDisbursementSms}, never directly — it masks the number. */
    public static final String SMS_MSG = "Your loan of $%s with ref # %s has been disbursed to your Innbucks wallet ending %s. Welcome to the Innbucks family";
    /** Render through {@link #disbursementSms}. */
    public static final String SMS_MSG_CONSUMER_FINANCE = "Your loan of $%s with ref # %s has been paid to %s, you can proceed to collect goods. Thank you for Banking with Innbucks";

    /** Prefix of the one deposit reference a loan's manual payout ever uses: {@code MD-<loan reference>}. */
    public static final String MANUAL_REFERENCE_PREFIX = "MD-";
    static final String MANUAL_PAYOUT_ACTOR = "manual-payout";

    /**
     * The wallet-disbursement SMS for {@code loan}, naming the wallet paid by its last
     * four digits. It used to print the full mobile number, which adds nothing
     * for its owner and is exposed wherever the text is shown or logged.
     */
    public static String walletDisbursementSms(Loan loan) {
        return String.format(SMS_MSG, loan.getDisbursedAmount(), loan.getReference(),
                MsisdnUtils.lastFourDigits(loan.payoutWalletNumber()));
    }

    /**
     * The payout SMS for {@code loan}, for both the automatic and the manual payout. A
     * consumer-finance loan pays the merchant, so its customer is told which merchant to collect
     * the goods from; any other loan names the customer's own wallet. Decided by the destination the
     * money was actually routed to ({@link PayoutDestination}).
     */
    public static String disbursementSms(Loan loan) {
        Merchant merchant = loan.getMerchant();
        if (PayoutDestination.of(loan).paysMerchant()) {
            return String.format(SMS_MSG_CONSUMER_FINANCE, loan.getDisbursedAmount(), loan.getReference(),
                    merchant == null ? "the merchant" : merchant.getCompanyName());
        }
        return walletDisbursementSms(loan);
    }

    private final LoanRepository loanRepository;
    private final LoanNotificationService loanNotificationService;
    private final LoanDisbursementRepository loanDisbursementRepository;
    private final DeductionCancellationService deductionCancellationService;
    private final DisbursementLedger disbursementLedger;
    private final CheckpointGate checkpointGate;
    private final AuthService authService;
    private final TransactionTemplate transactionTemplate;

    protected DisbursementService(LoanRepository loanRepository, LoanNotificationService loanNotificationService,
                                  LoanDisbursementRepository loanDisbursementRepository,
                                  DeductionCancellationService deductionCancellationService,
                                  DisbursementLedger disbursementLedger,
                                  CheckpointGate checkpointGate,
                                  AuthService authService,
                                  PlatformTransactionManager transactionManager) {
        this.loanRepository = loanRepository;
        this.loanNotificationService = loanNotificationService;
        this.loanDisbursementRepository = loanDisbursementRepository;
        this.deductionCancellationService = deductionCancellationService;
        this.disbursementLedger = disbursementLedger;
        this.checkpointGate = checkpointGate;
        this.authService = authService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Always a fresh transaction: the claim must be COMMITTED before InnBucks is called,
        // even if a future caller wraps disburse() in a transaction of its own.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Pays one deposit under {@link DisbursementRequest#getTransactionReference()}.
     * Implementations classify rather than throw: SUCCESS; FAILED only when nothing can
     * have been paid; UNKNOWN whenever the money may have moved.
     */
    public abstract DisbursementResponse disburseFunds(DisbursementRequest request);

    public abstract LoanAccountCreationResponse createLoanAccount(Loan loan);

    public abstract LoanDisbursementStatusResponse checkLoanDisbursementStatus(Loan loan);

    /** The deposit reference every manual payout attempt for this loan carries. */
    public static String manualReference(Loan loan) {
        return MANUAL_REFERENCE_PREFIX + loan.getReference();
    }

    /**
     * RECOVERY-ONLY manual payout through the InnBucks deposit rail. The normal rail is the
     * pre-approved booking ({@code LoanBookingJob}), on which InnBucks books AND pays;
     * this pays only a loan that booking definitively did not pay, and never pays twice:
     * <ol>
     *   <li><b>Claim</b> — under the loan's row lock, check eligibility (the checkpoints before booking among
     *       it, such as the payout authorisation of FR-SSB-018) and that the caller is a second person, then COMMIT
     *       a PENDING attempt row carrying the loan's stable reference before InnBucks is called. That
     *       row is the write-ahead record: a crash or a timeout from here on leaves it PENDING,
     *       and a PENDING row blocks every further attempt. No lock is held across the call.</li>
     *   <li><b>Pay</b> — one deposit; this method never retries it.</li>
     *   <li><b>Settle</b> — SUCCESS marks the row and the loan SUCCESS and posts the payout to the
     *       ledger, all in one transaction; a definite refusal marks
     *       the row FAILED and leaves the loan payable later (under the same reference); anything
     *       else leaves the row PENDING — in doubt, for an operator to confirm with InnBucks.</li>
     * </ol>
     *
     * @throws NotFoundException               unknown loan
     * @throws DisbursementNotAllowedException the loan is not eligible; nothing was sent
     * @throws AccessDeniedException           the caller originated the loan, approved it at Credit or is a party to
     *                                         it; nothing was sent
     */
    public ManualDisbursementResponse disburse(Long loanId) {
        Claim claim = transactionTemplate.execute(status -> claim(loanId));

        DisbursementResponse response;
        try {
            response = disburseFunds(claim.request());
        } catch (RuntimeException ex) {
            // disburseFunds classifies rather than throws; an escape means nobody knows what happened.
            log.error("Manual payout {} for loan {} threw; holding it in doubt", claim.reference(), loanId, ex);
            response = DisbursementResponse.builder()
                    .status(DisbursementStatus.UNKNOWN)
                    .message(ex.getClass().getSimpleName() + ": " + ex.getMessage())
                    .build();
        }
        log.info("Manual payout {} for loan {}: {}", claim.reference(), loanId, response);

        final DisbursementResponse answer = response;
        Settled settled;
        try {
            settled = transactionTemplate.execute(status -> settle(claim, answer));
        } catch (RuntimeException ex) {
            // The claim committed the attempt PENDING, and that is now the truth: InnBucks may
            // have paid and we could not record it.
            log.error("Could not record the {} outcome of manual payout {} for loan {}",
                    answer.getStatus(), claim.reference(), loanId, ex);
            return inDoubt(claim.reference(),
                    "InnBucks answered " + answer.getStatus() + " but the outcome could not be recorded.");
        }

        if (settled.result().getOutcome() == Outcome.DISBURSED) {
            notifyDisbursed(settled.loan());
        }
        return settled.result();
    }

    private Claim claim(Long loanId) {
        Loan loan = loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new NotFoundException("Loan %d not found".formatted(loanId)));
        String reference = manualReference(loan);
        requireEligible(loan, reference);
        requireSecondPerson(loan);

        // Same destination the pre-approved booking would have paid, frozen at credit approval: a
        // consumer-finance loan pays the merchant's settlement account, never the customer.
        PayoutDestination payee = PayoutDestination.of(loan);
        DisbursementRequest request = DisbursementRequest.builder()
                .amount(loan.getDisbursedAmount())
                .mobileNumber(loan.payoutWalletNumber())
                .reference(loan.getReference())
                .transactionReference(reference)
                .disbursementType(payee.type())
                .accountNumber(payee.merchantAccount())
                .build();

        loan.setDisbursementAttempts(loan.getDisbursementAttempts() == null ? 1 : loan.getDisbursementAttempts() + 1);
        loan.setDisbursementReference(reference);
        // A paid loan keeps its repayment: off the cancellation queue before any money can move.
        String unflaggedReason = deductionCancellationService.withdrawForPayout(loan, reference);
        loanRepository.save(loan);

        LoanDisbursement attempt = new LoanDisbursement();
        attempt.setLoan(loan);
        attempt.setDisbursementStatus(LoanDisbursementStatus.PENDING);
        attempt.setDisbursementReference(reference);
        attempt.setDisbursementStatusMessage("Sent to InnBucks; outcome not yet recorded");
        attempt = loanDisbursementRepository.save(attempt);

        log.info("Manual payout {} claimed for loan {} (attempt {})", reference, loanId, loan.getDisbursementAttempts());
        return new Claim(loan.getId(), attempt.getId(), reference, request, unflaggedReason);
    }

    /** Every refusal is a 409 naming the reason; nothing has been sent when one is thrown. */
    private void requireEligible(Loan loan, String reference) {
        String loanRef = loan.getReference();
        if (loan.getDisbursementStatus() == LoanDisbursementStatus.SUCCESS) {
            throw notAllowed("Loan %s is already disbursed (reference %s)"
                    .formatted(loanRef, loan.getDisbursementReference()));
        }
        if (loan.getLoanApprovalStatus() != LoanApprovalStatus.APPROVED) {
            throw notAllowed("Loan %s is not SSB-approved (status %s)".formatted(loanRef, loan.getLoanApprovalStatus()));
        }
        if (loan.getInternalApprovalStatus() != InternalApprovalStatus.APPROVED) {
            throw notAllowed("Loan %s is not credit-approved (status %s)"
                    .formatted(loanRef, loan.getInternalApprovalStatus()));
        }
        if (loan.getDeductionCancellationStatus() == DeductionCancellationStatus.CANCELLED_EXTERNALLY) {
            throw notAllowed(("The payroll deduction of loan %s was recorded as cancelled at Ndasenda %s;"
                    + " paid now, the loan would have no repayment. A manual payout is not allowed")
                    .formatted(loanRef, deductionCancellationService.describeRecordedCancellation(loan)));
        }
        if (loan.getBookingFailureKind() == BookingFailureKind.AMBIGUOUS) {
            throw notAllowed(("The InnBucks booking of loan %s has an unknown outcome and may already have paid it."
                    + " The inquiry job keeps checking; confirm with InnBucks. A manual payout is not allowed")
                    .formatted(loanRef));
        }
        // Positive evidence only: a FAILED account with no recorded refusal (every loan failed
        // before this was tracked) may be a booking that timed out after InnBucks paid it.
        if (loan.getBookingFailureKind() != BookingFailureKind.REFUSED
                || loan.getLoanAccountStatus() != LoanAccountStatus.FAILED) {
            throw notAllowed(("InnBucks has not definitively refused the booking of loan %s (account status %s),"
                    + " so the booking may pay it. Manual payout is recovery-only")
                    .formatted(loanRef, loan.getLoanAccountStatus()));
        }
        for (LoanDisbursement attempt : loanDisbursementRepository.findByLoanId(loan.getId())) {
            String attemptRef = attempt.getDisbursementReference();
            if (attempt.getDisbursementStatus() == LoanDisbursementStatus.SUCCESS) {
                throw notAllowed("Loan %s was already paid manually (reference %s)".formatted(loanRef, attemptRef));
            }
            if (attempt.getDisbursementStatus() != LoanDisbursementStatus.FAILED) {
                throw notAllowed(("An earlier manual payout of loan %s (reference %s) is in doubt."
                        + " Confirm with InnBucks whether it was paid before anything else is done")
                        .formatted(loanRef, attemptRef));
            }
            if (!reference.equals(attemptRef)) {
                // Written before refusals were told apart from timeouts: that FAILED may have paid.
                throw notAllowed(("An earlier manual payout of loan %s (reference %s) predates in-doubt tracking,"
                        + " so its FAILED status does not prove nothing was paid. Confirm with InnBucks")
                        .formatted(loanRef, attemptRef));
            }
        }
        requireClearedCheckpoints(loan);

        Merchant merchant = loan.getMerchant();
        PayoutDestination payee = PayoutDestination.of(loan);
        if (payee.type() == null) {
            throw notAllowed("Loan %s has no merchant disbursement type, so there is no destination to pay"
                    .formatted(loanRef));
        }
        if (payee.paysMerchant() && isBlank(payee.merchantAccount())) {
            throw notAllowed("Merchant %s of loan %s has no settlement account to pay"
                    .formatted(merchant == null ? null : merchant.getCompanyName(), loanRef));
        }
        if (!payee.paysMerchant() && isBlank(loan.payoutWalletNumber())) {
            throw notAllowed("Loan %s has no customer wallet number to pay".formatted(loanRef));
        }
        if (payee.frozen() && payee.differsFrom(merchant)) {
            log.warn("Loan {}: merchant payout settings changed after credit approved it; paying the approved"
                    + " {} destination, not the merchant's current one", loanRef, payee.type());
        }
        BigDecimal amount = loan.getDisbursedAmount();
        if (amount == null || amount.signum() <= 0) {
            throw notAllowed("Loan %s has no disbursement amount".formatted(loanRef));
        }
    }

    /**
     * This pays in place of the booking, so it waits for the checkpoints the booking waited for: those before booking
     * (FR-SSB-014), the payout authorisation of FR-SSB-018 among them. The booking job asks
     * {@link CheckpointGate#holding} under the loan's lock when it claims the booking and books no loan one holds, so
     * every checkpoint in force at that claim must have CLEARED the loan, and one that declined it refuses. Never
     * looser than the booking.
     *
     * <p>A checkpoint switched on after the booking was claimed (and so after InnBucks refused it) does not hold this
     * payout. {@code activeSince} means here what it means to the booking job: a loan still at the point when a
     * checkpoint comes on waits for it from then, and a loan already past it is not held by it
     * ({@code CheckpointQueue.atHoldPoint}: the booking has left). Had InnBucks accepted the booking, it would have
     * paid this loan without that checkpoint; and since the loan is past its point, it can neither wait nor be cleared
     * there, so counting it would strand the loan for good. A refused booking with no claim recorded was sent before
     * claims were recorded, which predates checkpoints, so none was in force for it.
     */
    private void requireClearedCheckpoints(Loan loan) {
        checkpointGate.notCleared(HoldPoint.BEFORE_BOOKING, loan, loan.getBookingClaimedAt()).ifPresent(held -> {
            String loanRef = loan.getReference();
            String stage = held.stage().getName();
            throw notAllowed(held.declined()
                    ? "Loan %s was declined at %s. A manual payout is not allowed".formatted(loanRef, stage)
                    : ("Loan %s is waiting for %s, which was switched on before its booking was sent and has not"
                    + " cleared it. A manual payout is not allowed").formatted(loanRef, stage));
        });
    }

    /**
     * The payout must be a second person's (FR-SSB-018). It is a decision to pay a loan Credit has approved, so it
     * takes the bars a checkpoint before booking takes, no more and no fewer ({@code CheckpointService
     * .requireNoConflictOfInterest}, with {@code WorkflowStage.barsCreditApprover} true at BEFORE_BOOKING): not whoever
     * originated the loan, nor whoever approved it at Credit, nor a party to it (FR-PBL-029). In the same order, and
     * like a checkpoint decision only once the loan is known to be payable. Assignment does not carry over: a payout
     * has no work item.
     */
    private void requireSecondPerson(Loan loan) {
        String username = authService.getLoggedInUsername();
        String loanRef = loan.getReference();
        if (SegregationOfDuties.originated(loan, username)) {
            throw new AccessDeniedException(("Loan %s was originated by %s, who cannot also pay it out; another"
                    + " SUPER_ADMIN must").formatted(loanRef, username));
        }
        if (SegregationOfDuties.approved(loan, username)) {
            throw new AccessDeniedException(("Loan %s was approved by %s, who cannot also pay it out; another"
                    + " SUPER_ADMIN must").formatted(loanRef, username));
        }
        User user = authService.getLoggedInUser();
        if (SegregationOfDuties.isPartyTo(loan, user)) {
            throw new AccessDeniedException("%s is a party to loan %s and cannot pay it out; another SUPER_ADMIN must"
                    .formatted(username, loanRef));
        }
    }

    private Settled settle(Claim claim, DisbursementResponse response) {
        Loan loan = loanRepository.findByIdForUpdate(claim.loanId())
                .orElseThrow(() -> new IllegalStateException("Loan %d vanished mid-payout".formatted(claim.loanId())));
        LoanDisbursement attempt = loanDisbursementRepository.findById(claim.attemptId())
                .orElseThrow(() -> new IllegalStateException("Attempt %d vanished mid-payout".formatted(claim.attemptId())));
        String reference = claim.reference();
        String detail = response.getMessage() == null ? "no detail" : response.getMessage();
        DisbursementStatus status = response.getStatus() == null ? DisbursementStatus.UNKNOWN : response.getStatus();

        ManualDisbursementResponse result = switch (status) {
            case SUCCESS -> {
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                String paid = response.getApprovalCode() == null
                        ? "Paid by manual recovery payout %s".formatted(reference)
                        : "Paid by manual recovery payout %s (InnBucks auth %s)".formatted(reference, response.getApprovalCode());
                attempt.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
                attempt.setDateDisbursed(now);
                attempt.setDisbursementStatusMessage(truncate(paid));
                loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
                loan.setDateDisbursed(now);
                loan.setDisbursementMerchantAccountNumber(claim.request().getAccountNumber());
                loan.setDisbursementStatusMessage(truncate(paid));
                // Posted here, with the payout: a recovery payout pays a loan whose saga has usually
                // already compensated, and a terminal saga posts nothing, so this is the only place
                // it can be. The loan's reference is MD-<ref> since the claim, so it posts as DISB-MD-<ref>.
                disbursementLedger.recordPayout(loan, MANUAL_PAYOUT_ACTOR);
                // The customer's SMS is worded after this commits (notifyDisbursed), and a merchant payout names the
                // merchant, which is LAZY: loaded here, while the loan's session is open.
                Hibernate.initialize(loan.getMerchant());
                yield ManualDisbursementResponse.builder().outcome(Outcome.DISBURSED).reference(reference)
                        .message(paid).build();
            }
            case FAILED -> {
                // InnBucks' own refusal, or a call that never left: either way nothing was paid.
                String refused = "Manual payout %s was not paid: %s".formatted(reference, detail);
                attempt.setDisbursementStatus(LoanDisbursementStatus.FAILED);
                attempt.setDisbursementStatusMessage(truncate(refused));
                loan.setDisbursementStatusMessage(truncate(refused));
                reflag(loan, claim, claim.unflaggedReason());
                yield ManualDisbursementResponse.builder().outcome(Outcome.REFUSED).reference(reference)
                        .message(refused + ". Nothing was paid; the loan may be tried again.").build();
            }
            case UNKNOWN -> {
                // The attempt stays PENDING: that is what blocks every further manual payout.
                String unknown = "Outcome of manual payout %s unknown: %s".formatted(reference, detail);
                attempt.setDisbursementStatusMessage(truncate(unknown));
                loan.setDisbursementStatusMessage(truncate(unknown));
                // The customer may now hold the money: confirm with InnBucks before cancelling anything.
                reflag(loan, claim, DeductionCancellationService.REASON_BOOKING_IN_DOUBT);
                yield inDoubt(reference, unknown + ".");
            }
        };
        loanDisbursementRepository.save(attempt);
        loanRepository.save(loan);
        return new Settled(loan, result);
    }

    /** Puts back a flag the claim withdrew; a loan that was not flagged stays as it was. Saved by the caller. */
    private void reflag(Loan loan, Claim claim, String reason) {
        if (claim.unflaggedReason() != null) {
            deductionCancellationService.markRequired(loan, reason, MANUAL_PAYOUT_ACTOR, "system");
        }
    }

    private static ManualDisbursementResponse inDoubt(String reference, String detail) {
        return ManualDisbursementResponse.builder()
                .outcome(Outcome.IN_DOUBT)
                .reference(reference)
                .message(detail + " Confirm with InnBucks whether " + reference + " was paid;"
                        + " every further manual payout of this loan is blocked until then.")
                .build();
    }

    private static DisbursementNotAllowedException notAllowed(String message) {
        return new DisbursementNotAllowedException(message);
    }

    /** The customer SMS must never undo a recorded payout: it runs after the commit, and the notice cannot throw. */
    private void notifyDisbursed(Loan loan) {
        log.info("Loan {} paid to its {}: notifying the customer", loan.getReference(), PayoutDestination.of(loan).type());
        loanNotificationService.notify(loan, LoanNotice.PAID, disbursementSms(loan));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The status-message columns are VARCHAR(255). */
    private static String truncate(String message) {
        return message.length() <= 255 ? message : message.substring(0, 252) + "...";
    }

    /** {@code unflaggedReason}: the cancellation reason the claim withdrew, or null if the loan was not flagged. */
    private record Claim(Long loanId, Long attemptId, String reference, DisbursementRequest request,
                         String unflaggedReason) {
    }

    private record Settled(Loan loan, ManualDisbursementResponse result) {
    }
}
