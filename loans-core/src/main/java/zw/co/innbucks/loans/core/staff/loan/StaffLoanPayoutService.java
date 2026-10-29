package zw.co.innbucks.loans.core.staff.loan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.InnbucksDisbursementService;
import zw.co.innbucks.loans.core.disbursements.PreApprovedBookingResult;
import zw.co.innbucks.loans.core.disbursements.PreApprovedLoanBooking;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pays a cash Staff Grocery Loan into the borrower's own account. This is the only code that sends the bank's
 * pre-approved booking for one ({@link InnbucksDisbursementService#bookPreApproved}), and the bank books AND pays on
 * that one call, so a payout is never sent twice. Every send is:
 * <ol>
 *   <li><b>Claimed</b>, READY to SENDING, under the register lock and then the loan's row lock, in a transaction that
 *       commits before the bank is called. A loan already claimed, settled or cancelled is left alone; one a guard
 *       refuses stays READY, saying why ({@link Outcome#HELD}).</li>
 *   <li><b>Sent once</b>, with no transaction open, under the loan's own reference.</li>
 *   <li><b>Settled</b> in a transaction of its own, only while the loan still carries this claim: BOOKED (a 2xx whose
 *       envelope says 000, or carries none), REFUSED (the bank's own 4xx: nothing was booked), IN_DOUBT (an answer that
 *       does not say: a 2xx with any other responseCode, a timeout, a 5xx, a 408, a 409, a redirect), or READY again
 *       when it provably never reached the bank or the bank did not process it (a login that failed, a connection
 *       never opened, a request that could not be built, a 401, 403, 407, 425 or 429).</li>
 * </ol>
 * No answer cancels the loan: a REFUSED payout keeps the loan, and its slot, until a person sends it again or cancels
 * it. An IN_DOUBT payout is settled only by the bank's inquiry or a person ({@link StaffLoanPayoutStatusService}), never
 * by sending it again. A claim never settled (the process died mid-call) is held IN_DOUBT by the status check once it
 * is stale. A 2xx is never lost: one that lands after that hold makes the payout BOOKED, and one whose settle cannot
 * be saved is still recorded as seen by the bank, so nobody can record it as never booked.
 *
 * <p>It runs on {@link StaffLoanPayoutDispatcher}'s one thread: never in a request, never inside the acceptance, and
 * never on a schedule.
 */
@Slf4j
@Service
public class StaffLoanPayoutService {

    public static final String SYSTEM_ACTOR = "staff-loan-payout";
    /** The loan is repaid from the next salary: one month, one repayment. */
    static final int TENURE_MONTHS = 1;

    static final String BOOKED = "STAFF_LOAN_PAYOUT_BOOKED";
    static final String REFUSED = "STAFF_LOAN_PAYOUT_REFUSED";
    static final String IN_DOUBT = "STAFF_LOAN_PAYOUT_IN_DOUBT";
    static final String NOT_SENT = "STAFF_LOAN_PAYOUT_NOT_SENT";
    static final String HELD = "STAFF_LOAN_PAYOUT_HELD";
    static final String NOT_RECORDED = "STAFF_LOAN_PAYOUT_NOT_RECORDED";
    /** A 2xx to the booking that could not be recorded on the loan: the bank took it. */
    static final String BOOKED_NOT_RECORDED = "STAFF_LOAN_PAYOUT_BOOKED_NOT_RECORDED";

    /**
     * Statuses the bank answers to a request it did not process: the payout was not booked, and may be sent again. All
     * but 403 refuse the call itself (credentials, a proxy, throttling); a 403 may be the bank's edge refusing this one
     * request, so it does not stop a pass.
     */
    private static final int[] NOT_PROCESSED = {401, 403, 407, 425, 429};
    private static final int REFUSED_THIS_REQUEST = 403;
    /** A national ID as the register stores it, 632345678B42; sent as the bank's samples write it, 63-2345678B42. */
    private static final Pattern NATIONAL_ID = Pattern.compile("(\\d{2})(\\d{6,7}[A-Z]\\d{2})");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** What became of one payout. */
    public enum Outcome {
        /** The bank took it (2xx); its inquiry says when it has landed. */
        BOOKED,
        /** The bank refused it (its own 4xx): nothing was booked, and the loan stays for a person to decide. */
        REFUSED,
        /** The answer does not say whether it was booked: held for the bank's inquiry, never sent again. */
        IN_DOUBT,
        /** It never reached the bank, or the bank did not process it: READY again, sent on a later pay-out. */
        NOT_SENT,
        /**
         * NOT_SENT for a reason of its own (the bank's edge refused this one request with a 403, or the booking could
         * not be built): READY again, and a pass goes on to the next payout.
         */
        PASSED_OVER,
        /** A guard refused to send it: it stays READY, with why. */
        HELD,
        /** The bank answered, and what it said could not be saved: the claim stands, never sent again. */
        NOT_RECORDED,
        /** Not this caller's to send (claimed, settled or cancelled since it was listed): the bank was not asked. */
        SKIPPED;

        /**
         * Whether a pass over the READY payouts may go on to the next one. It stops at anything that may be the bank's
         * trouble (not sent, refused, in doubt, not recorded): every payout behind it would most likely end the same
         * way, and each in doubt is one an operator must settle. It goes on past one passed over: that payout's reason
         * is its own, and the pass never comes back to it.
         */
        public boolean carriesOn() {
            return this == BOOKED || this == HELD || this == SKIPPED || this == PASSED_OVER;
        }
    }

    /** A payout claimed for sending, and what is sent. */
    record Claim(Long loanId, String reference, LocalDateTime claimedAt, PreApprovedLoanBooking booking) {
        @Override
        public String toString() {
            return "Claim[loanId=" + loanId + ", reference=" + reference + ", claimedAt=" + claimedAt + "]";
        }
    }

    /** What the claim step decided: a claim to send, or an outcome with nothing sent. */
    private record Claimed(Claim claim, Outcome outcome, String reference, String held, boolean newlyHeld) {
    }

    /** What the settle recorded, and whether the 2xx landed on a payout already held IN_DOUBT (or BOOKED). */
    private record Settled(Outcome outcome, boolean late) {
    }

    private final StaffLoanRepository loanRepository;
    private final StaffMemberRepository memberRepository;
    private final StaffLoanProperties properties;
    private final InnbucksDisbursementService bank;
    private final StaffLoanPayoutMetrics metrics;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;
    private final TransactionTemplate transactionTemplate;

    public StaffLoanPayoutService(StaffLoanRepository loanRepository, StaffMemberRepository memberRepository,
                                  StaffLoanProperties properties, InnbucksDisbursementService bank,
                                  StaffLoanPayoutMetrics metrics, AuditService auditService,
                                  MarketTimeZone marketTimeZone, PlatformTransactionManager transactionManager) {
        this.loanRepository = loanRepository;
        this.memberRepository = memberRepository;
        this.properties = properties;
        this.bank = bank;
        this.metrics = metrics;
        this.auditService = auditService;
        this.marketTimeZone = marketTimeZone;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Always a fresh transaction: the claim must be COMMITTED before the bank is called, and the outcome must
        // commit or roll back on its own.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Claims, sends and settles one cash loan's payout. Never throws for anything the bank or the database does; the
     * outcome says what happened.
     *
     * @throws IllegalStateException called with a transaction open: the bank must never be called while a
     *                               connection, or a lock, is held
     */
    public Outcome payOut(Long staffLoanId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Staff loan " + staffLoanId + ": a payout is never sent inside a"
                    + " transaction");
        }
        Claimed claimed;
        try {
            claimed = transactionTemplate.execute(status -> claim(staffLoanId));
        } catch (RuntimeException ex) {
            // The claim did not commit, so nothing was sent.
            log.error("Could not claim staff loan {} for its payout; nothing was sent", staffLoanId, ex);
            metrics.count(StaffLoanPayoutMetrics.Event.NOT_SENT);
            return Outcome.NOT_SENT;
        }
        if (claimed == null) {
            return Outcome.SKIPPED;
        }
        if (claimed.claim() == null) {
            if (claimed.outcome() == Outcome.HELD) {
                held(staffLoanId, claimed.reference(), claimed.held(), claimed.newlyHeld());
            }
            return claimed.outcome();
        }
        Claim claim = claimed.claim();
        PreApprovedBookingResult result;
        try {
            result = bank.bookPreApproved(claim.booking());
        } catch (RuntimeException ex) {
            // It does not throw; if it ever did, the booking may have left.
            result = new PreApprovedBookingResult(0, null, null, ex);
        }
        if (result == null) {
            result = new PreApprovedBookingResult(0, null, null,
                    new IllegalStateException("The booking returned no outcome"));
        }
        Outcome outcome = classify(result);
        PreApprovedBookingResult answer = result;
        try {
            Settled settled = transactionTemplate.execute(status -> settle(claim, answer, outcome));
            recorded(claim, answer, settled);
            return settled.outcome();
        } catch (RuntimeException ex) {
            if (outcome == Outcome.BOOKED) {
                markSeenByBank(claim, answer);
            }
            notRecorded(claim, answer, outcome, "settle_failed", ex);
            return Outcome.NOT_RECORDED;
        }
    }

    /**
     * What one answer to the booking means:
     * <ul>
     *   <li>BOOKED for a 2xx whose envelope says {@code responseCode} 000, or that carries no envelope (only the status
     *       to go on, as the SSB booking reads it);</li>
     *   <li>IN_DOUBT for a 2xx with any other {@code responseCode} (the bank has answered a refusal inside a 2xx
     *       before, so it is not taken as booked, and its inquiry decides), a redirect (a POST is never followed: a 302
     *       or 303 after one means it WAS processed), a 408, a 409, a 5xx, or no answer read after the request may have
     *       left;</li>
     *   <li>NOT_SENT when it provably never reached the bank, or the bank did not process it (401, 407, 425, 429, also
     *       on the replay after a 401); PASSED_OVER for the same with a reason of its own (a 403, or a booking that
     *       could not be built);</li>
     *   <li>REFUSED for every other 4xx, the bank's own "no".</li>
     * </ul>
     */
    static Outcome classify(PreApprovedBookingResult result) {
        if (!result.wasAnswered()) {
            if (result.notBuilt()) {
                return Outcome.PASSED_OVER;
            }
            return result.notSent() ? Outcome.NOT_SENT : Outcome.IN_DOUBT;
        }
        int status = result.httpStatus();
        if (status >= 200 && status < 300) {
            return result.responseCode() == null || result.approved() ? Outcome.BOOKED : Outcome.IN_DOUBT;
        }
        if (status == REFUSED_THIS_REQUEST) {
            return Outcome.PASSED_OVER;
        }
        if (Arrays.stream(NOT_PROCESSED).anyMatch(code -> code == status)) {
            return Outcome.NOT_SENT;
        }
        if (status >= 400 && status < 500 && status != 408 && status != 409) {
            return Outcome.REFUSED;
        }
        return Outcome.IN_DOUBT;
    }

    private Claimed claim(Long id) {
        // The register lock first, as every register writer takes it: a claim and a register approval (which cancels
        // a leaver's loan) never interleave, and the member read below is the register as it now stands.
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        StaffLoan loan = loanRepository.lockById(id).orElse(null);
        if (loan == null || loan.getPayoutMethod() != StaffLoanPayoutMethod.CASH
                || loan.getStatus() != StaffLoanStatus.AWAITING_DISBURSEMENT
                || loan.getPayoutStatus() != StaffLoanPayoutStatus.READY) {
            log.info("Staff loan {} is not ready to pay out (claimed, settled or cancelled since); skipped", id);
            return null;
        }
        StaffMember member = memberRepository.findById(loan.getStaffMemberId()).orElse(null);
        String why = refusal(loan, member);
        PreApprovedLoanBooking booking = null;
        if (why == null) {
            try {
                booking = booking(loan, member, properties.getPayout());
            } catch (RuntimeException ex) {
                log.error("The payout booking of staff loan {} could not be built", loan.getReference(), ex);
                why = "Not sent: the booking could not be built (" + ex.getClass().getSimpleName() + ")";
            }
        }
        if (why != null) {
            boolean changed = !why.equals(loan.getPayoutMessage());
            if (changed) {
                loan.holdPayout(why);
                loanRepository.save(loan);
            }
            return new Claimed(null, Outcome.HELD, loan.getReference(), why, changed);
        }
        // Microseconds survive the round trip to the column exactly, so the settle can recognise its own claim.
        LocalDateTime claimedAt = marketTimeZone.nowUtc().truncatedTo(ChronoUnit.MICROS);
        loan.claimPayout(claimedAt);
        loanRepository.save(loan);
        log.info("Staff loan {} claimed for its payout at {}", loan.getReference(), claimedAt);
        return new Claimed(new Claim(loan.getId(), loan.getReference(), claimedAt, booking), null,
                loan.getReference(), null, false);
    }

    /** Why this payout must not be sent now, or null to send it. */
    private String refusal(StaffLoan loan, StaffMember member) {
        if (member == null || member.getEmploymentStatus() != StaffEmploymentStatus.ACTIVE) {
            return "Not sent: the borrower is " + (member == null ? "not on the staff register"
                    : member.getEmploymentStatus() + " on the staff register, not ACTIVE")
                    + ". Credit should cancel this loan.";
        }
        LocalDate today = marketTimeZone.today();
        int minDays = properties.getPayout().getMinDaysBeforeDue();
        if (loan.getDueDate() == null || ChronoUnit.DAYS.between(today, loan.getDueDate()) < minDays) {
            return "Not sent: the repayment date (" + loan.getDueDate() + ") is fewer than " + minDays
                    + " days away, too close to the repayment date. Credit should cancel this loan, or the borrower"
                    + " re-apply.";
        }
        StaffLoanPayoutDestination destination = loan.getPayoutDestination();
        String account = destination == null ? null : destination.accountOf(member);
        if (account == null || !account.equals(loan.getPayoutAccount())) {
            return "Not sent: the borrower's " + (destination == StaffLoanPayoutDestination.BR_ACCOUNT
                    ? "account number" : "phone") + " on the staff register is no longer the "
                    + (destination == null ? "account" : destination.label())
                    + " they accepted this loan to be paid into. Credit should cancel this loan; the borrower may"
                    + " re-apply.";
        }
        if (loanRepository.existsByStaffMemberIdAndPayoutPaidAfterCancelAtIsNotNull(loan.getStaffMemberId())) {
            return "Not sent: the bank holds a payout of this borrower's that was confirmed never booked. Settle that"
                    + " with the bank first.";
        }
        if (isBlank(properties.getPayout().getProduct())) {
            return "Not sent: loans.staff-loans.payout.product (STAFF_LOANS_PAYOUT_PRODUCT) is not set.";
        }
        if (loanType(loan, properties.getPayout()) == null) {
            return "Not sent: loans.staff-loans.payout.loan-type (STAFF_LOANS_PAYOUT_LOAN_TYPE) is not set, and a "
                    + destination + " payout needs it.";
        }
        return null;
    }

    private Settled settle(Claim claim, PreApprovedBookingResult result, Outcome outcome) {
        StaffLoan loan = loanRepository.lockById(claim.loanId()).orElse(null);
        LocalDateTime now = marketTimeZone.nowUtc();
        String answer = result.wasAnswered() ? result.answer() : describe(result.failure());
        if (loan == null || !holdsClaim(loan, claim)) {
            if (outcome == Outcome.BOOKED && loan != null && heldForInquiry(loan)) {
                // The stale-claim hold took it while the bank was answering: the bank did take it, and nobody may
                // ever record it as never booked.
                loan.payoutBookedLate(now, answer);
                loanRepository.save(loan);
                return new Settled(Outcome.BOOKED, true);
            }
            notRecorded(claim, result, outcome, "claim_superseded", null);
            return new Settled(Outcome.NOT_RECORDED, false);
        }
        loan.payoutApplyAnswered(answer);
        switch (outcome) {
            case BOOKED -> loan.payoutBooked(now, answer);
            case NOT_SENT, PASSED_OVER -> loan.releasePayoutClaim(result.wasAnswered()
                    ? "Not booked: the bank did not process it (HTTP " + result.httpStatus() + "). It is ready to pay;"
                    + " send it again with Pay out."
                    : "Not sent: " + describe(result.failure()) + ". It is ready to pay; send it again with Pay out"
                    + (result.notBuilt() ? " once what stopped it is put right." : " once the bank can be reached."),
                    answer);
            case REFUSED -> loan.payoutRefused("The bank refused it (HTTP " + result.httpStatus() + "), so nothing"
                    + " was booked. Send it again with Pay out once what the bank said is put right, or Credit"
                    + " cancels the loan.", answer);
            default -> loan.payoutInDoubt((result.wasAnswered()
                    ? "The bank answered HTTP " + result.httpStatus()
                    + (result.responseCode() == null ? "" : " with responseCode " + result.responseCode())
                    : "The bank did not answer (" + describe(result.failure()) + ")")
                    + ", which does not say whether it booked this payout. Held for the bank's inquiry; it is"
                    + " never sent again.", answer);
        }
        loanRepository.save(loan);
        return new Settled(outcome, false);
    }

    /** A cash payout awaiting disbursement that the bank's inquiry settles: what a late 2xx can still be recorded on. */
    private static boolean heldForInquiry(StaffLoan loan) {
        return loan.getPayoutMethod() == StaffLoanPayoutMethod.CASH
                && loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT
                && loan.getPayoutStatus() != null && loan.getPayoutStatus().awaitsInquiry();
    }

    /**
     * The bank took a payout whose settle could not be saved: at least record that the bank has seen it, in a short
     * transaction of its own, so nobody can record it as never booked. Best effort: the audit row is the rest of the
     * evidence.
     */
    private void markSeenByBank(Claim claim, PreApprovedBookingResult result) {
        try {
            Integer marked = transactionTemplate.execute(status -> loanRepository.markPayoutSeenByBank(
                    claim.loanId(), marketTimeZone.nowUtc(), result.answer()));
            log.warn("Staff loan {}: its payout's 2xx could not be settled; {}", claim.reference(),
                    marked != null && marked > 0 ? "recorded as seen by the bank" : "it was already seen by the bank");
        } catch (RuntimeException ex) {
            log.error("Staff loan {}: its payout's 2xx could not be recorded as seen by the bank either",
                    claim.reference(), ex);
        }
    }

    /** Still this send's to settle: the loan carries this claim and is still being sent. */
    private static boolean holdsClaim(StaffLoan loan, Claim claim) {
        return loan.getPayoutMethod() == StaffLoanPayoutMethod.CASH
                && loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT
                && loan.getPayoutStatus() == StaffLoanPayoutStatus.SENDING
                && claim.claimedAt().equals(loan.getPayoutClaimedAt());
    }

    /** Counted and audited once the settle has committed, so the trail says only what was recorded. */
    private void recorded(Claim claim, PreApprovedBookingResult result, Settled settled) {
        String http = http(result);
        switch (settled.outcome()) {
            case BOOKED -> {
                metrics.count(StaffLoanPayoutMetrics.Event.BOOKED);
                if (settled.late()) {
                    log.error("STAFF LOAN PAYOUT BOOKED LATE: the bank took {} (HTTP {}) after it was held IN_DOUBT"
                            + " for its inquiry; recorded BOOKED (audited)", claim.reference(), result.httpStatus());
                } else {
                    log.info("Staff loan {}: the bank took its payout (HTTP {})", claim.reference(),
                            result.httpStatus());
                }
                audit(BOOKED, claim, http + ";claimedAt:" + claim.claimedAt() + ";account:"
                        + MsisdnUtils.mask(accountOf(claim)) + (settled.late() ? ";late:true" : ""));
            }
            case REFUSED -> {
                metrics.count(StaffLoanPayoutMetrics.Event.REFUSED);
                log.warn("Staff loan {}: the bank refused its payout (HTTP {}); nothing was booked, and the loan waits"
                        + " for a person", claim.reference(), result.httpStatus());
                audit(REFUSED, claim, http);
            }
            case NOT_SENT, PASSED_OVER -> {
                metrics.count(StaffLoanPayoutMetrics.Event.NOT_SENT);
                log.warn("Staff loan {}: its payout was not booked ({}); it is ready to pay again", claim.reference(),
                        http);
                audit(NOT_SENT, claim, http);
            }
            case IN_DOUBT -> {
                metrics.count(StaffLoanPayoutMetrics.Event.IN_DOUBT);
                log.error("STAFF LOAN PAYOUT IN DOUBT: {} ({}) may or may not have been booked and paid by the bank;"
                        + " held for its inquiry and never sent again (audited)", claim.reference(), http);
                audit(IN_DOUBT, claim, http);
            }
            default -> {
                // NOT_RECORDED was audited where it was found.
            }
        }
    }

    private void held(Long loanId, String reference, String why, boolean newly) {
        metrics.count(StaffLoanPayoutMetrics.Event.HELD);
        if (!newly) {
            return;
        }
        log.warn("Staff loan {}: payout held. {}", reference, why);
        audit(HELD, loanId, reference, "why:" + why);
    }

    /**
     * The bank answered, and that answer is not on the loan: the loan moved on under this send (the stale-claim hold
     * took it), or the settle could not be saved. The claim stands either way, so it is never sent again, and the
     * bank's inquiry settles it.
     */
    private void notRecorded(Claim claim, PreApprovedBookingResult result, Outcome outcome, String reason,
                             RuntimeException failure) {
        metrics.count(StaffLoanPayoutMetrics.Event.NOT_RECORDED);
        String http = http(result);
        log.error("STAFF LOAN PAYOUT NOT RECORDED: {} claimed at {} - the bank's answer ({}, {}) is not on the loan"
                        + " ({}); it is never sent again, and the bank's inquiry settles it (audited)",
                claim.reference(), claim.claimedAt(), outcome, http, reason, failure);
        // A 2xx has its own event, which "never booked" is refused on: the bank took it.
        audit(outcome == Outcome.BOOKED ? BOOKED_NOT_RECORDED : NOT_RECORDED, claim, "reason:" + reason
                + ";outcome:" + outcome + ";" + http + ";claimedAt:" + claim.claimedAt()
                + (failure == null ? "" : ";error:" + failure.getClass().getSimpleName()));
    }

    private static String http(PreApprovedBookingResult result) {
        return result.wasAnswered() ? "http:" + result.httpStatus()
                + (result.responseCode() == null ? "" : ";responseCode:" + result.responseCode())
                : "failure:" + result.failure().getClass().getSimpleName();
    }

    /**
     * What the bank is sent for one loan: only what the register and the loan hold, every other field left out. The
     * participant reference is the loan's own, which the bank's inquiry is keyed by.
     */
    static PreApprovedLoanBooking booking(StaffLoan loan, StaffMember member, StaffLoanProperties.Payout settings) {
        String[] names = names(loan.getFullName());
        boolean account = loan.getPayoutDestination() == StaffLoanPayoutDestination.BR_ACCOUNT;
        return new PreApprovedLoanBooking(
                loan.getReference(),
                names[0],
                names[1],
                idNumber(member.getNationalId()),
                loan.getAmount(),
                loan.getCurrency(),
                settings.getProduct() == null ? null : settings.getProduct().strip(),
                loanType(loan, settings),
                TENURE_MONTHS,
                // A wallet payout pays the wallet it was accepted for; an account payout names the borrower's phone.
                account ? loan.getMsisdn() : loan.getPayoutAccount(),
                account ? loan.getPayoutAccount() : null,
                isBlank(settings.getEmployerName()) ? null : settings.getEmployerName().strip(),
                loan.getEmployeeNumber(),
                member.getEngagementDate());
    }

    /** The booking's {@code type}: the one set, else PERSONAL for a loan paid into a wallet; null when neither. */
    static String loanType(StaffLoan loan, StaffLoanProperties.Payout settings) {
        if (!isBlank(settings.getLoanType())) {
            return settings.getLoanType().strip();
        }
        return loan.getPayoutDestination() == StaffLoanPayoutDestination.VEENGU_WALLET
                ? StaffLoanProperties.Payout.PERSONAL : null;
    }

    /** {first, last}: the last word is the surname, the rest the first names; one word is a surname alone. */
    static String[] names(String fullName) {
        String collapsed = fullName == null ? "" : WHITESPACE.matcher(fullName.strip()).replaceAll(" ");
        if (collapsed.isEmpty()) {
            return new String[]{null, null};
        }
        int last = collapsed.lastIndexOf(' ');
        return last < 0 ? new String[]{null, collapsed}
                : new String[]{collapsed.substring(0, last), collapsed.substring(last + 1)};
    }

    /** 632345678B42 as 63-2345678B42, the shape the bank's samples write; anything else as stored. */
    static String idNumber(String stored) {
        if (stored == null) {
            return null;
        }
        Matcher matcher = NATIONAL_ID.matcher(stored);
        return matcher.matches() ? matcher.group(1) + "-" + matcher.group(2) : stored;
    }

    private static String accountOf(Claim claim) {
        return claim.booking().settlementAccount() != null ? claim.booking().settlementAccount()
                : claim.booking().msisdn();
    }

    private void audit(String eventType, Claim claim, String detail) {
        audit(eventType, claim.loanId(), claim.reference(), detail);
    }

    private void audit(String eventType, Long loanId, String reference, String detail) {
        try {
            auditService.record(AuditLog.builder()
                    .eventType(eventType)
                    .entityType("STAFF_LOAN").entityId(String.valueOf(loanId))
                    .actorId(SYSTEM_ACTOR).channelUsed("system")
                    .detail("reference:" + reference + ";" + detail)
                    .correlationId(reference));
        } catch (RuntimeException ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while opening the
            // transaction; the log line above is the evidence, and the payout must carry on.
            log.error("Audit of staff loan {} ({}) failed", reference, eventType, ex);
        }
    }

    private static String describe(RuntimeException failure) {
        if (failure == null) {
            return "no answer";
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
