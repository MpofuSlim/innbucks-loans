package zw.co.innbucks.loans.core.staff.loan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.InnbucksLoanInquiry;
import zw.co.innbucks.loans.core.disbursements.InquiryAnswer;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.jobs.IdChunks;
import zw.co.innbucks.loans.core.loan.SegregationOfDuties;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationService;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationTemplate;
import zw.co.innbucks.loans.core.user.User;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Follows a cash Staff Grocery Loan's payout after it was sent, and lets a person settle one the bank could not: the
 * read-only half of the payout. It asks the bank's inquiry ({@link InnbucksLoanInquiry}, which cannot book) and
 * depends on nothing that sends a booking, so nothing here can pay a loan twice ({@code StaffLoanPayoutWiringTest}
 * pins it).
 *
 * <ul>
 *   <li><b>A claim never settled</b> (the process died mid-call) is held IN_DOUBT once it is
 *       {@code stale-claim-minutes} old, never sent again.</li>
 *   <li><b>The inquiry</b> ({@link #check}) is made with no transaction open; what it says is recorded under the loan's
 *       row lock. The bank holding it in a paid status, and its answer naming our reference, amount and account (each
 *       one it carries), is PAID: the loan is DISBURSED and the borrower told. Holding it in any other status makes an
 *       IN_DOUBT payout BOOKED; a status that says it declined it ({@code failed-statuses}) is recorded, once. "Not
 *       found" is recorded, once, and never fails a payout by itself. An answer that says neither changes nothing. A
 *       person can ask for one payout at once ({@link #checkNow}).</li>
 *   <li><b>A person who has asked the bank</b> records it paid ({@link #confirmPaid}), or never paid
 *       ({@link #confirmNotBooked}), which cancels the loan: for an IN_DOUBT payout the bank has never said it holds and
 *       has reported missing, or for one it holds in a status that says it declined it, in either case for
 *       {@code not-booked-min-age} and still lately. Neither is open to a party to the loan.</li>
 *   <li><b>A payout confirmed never paid is still watched</b> for {@code failed-watch}: the bank holding it after all,
 *       in any status but a declined one, is recorded, loudly, and holds that borrower's further payouts.</li>
 * </ul>
 */
@Slf4j
@Service
public class StaffLoanPayoutStatusService {

    public static final String SYSTEM_ACTOR = "staff-loan-payout-status";
    static final String PORTAL_CHANNEL = "admin-portal";

    static final String CLAIM_ABANDONED = "STAFF_LOAN_PAYOUT_CLAIM_ABANDONED";
    static final String NOT_FOUND = "STAFF_LOAN_PAYOUT_NOT_FOUND";
    static final String FOUND = "STAFF_LOAN_PAYOUT_FOUND";
    static final String PAID_OUT = "STAFF_LOAN_PAID_OUT";
    static final String ECHO_MISMATCH = "STAFF_LOAN_PAYOUT_ECHO_MISMATCH";
    static final String INTEREST_CHARGED = "STAFF_LOAN_PAYOUT_INTEREST_CHARGED";
    static final String UNKNOWN_BANK_STATUS = "STAFF_LOAN_PAYOUT_UNKNOWN_BANK_STATUS";
    static final String PAID_AFTER_CANCEL = "STAFF_LOAN_PAYOUT_PAID_AFTER_CANCEL";
    static final String DECLINED = "STAFF_LOAN_PAYOUT_DECLINED";
    static final String CONFIRMED_PAID = "STAFF_LOAN_PAYOUT_CONFIRMED_PAID";
    static final String CONFIRMED_NOT_BOOKED = "STAFF_LOAN_PAYOUT_CONFIRMED_NOT_BOOKED";

    /** The longest bank reference an operator's confirmation keeps. */
    static final int BANK_REFERENCE_LENGTH = 64;

    /** What one inquiry came to. */
    public enum Checked {
        /** The money is in the borrower's account: the loan is DISBURSED. */
        PAID,
        /** The bank holds it and has not paid it yet. */
        BOOKED,
        /** The bank says it paid it, but its answer names another reference, amount or account: not recorded paid. */
        ECHO_MISMATCH,
        /** The bank holds it in a status this service does not know: not recorded paid. */
        UNKNOWN_STATUS,
        /** The bank holds it in a status that says it declined it ({@code failed-statuses}): it did not pay it. */
        DECLINED,
        /** The bank says it holds no loan under the reference. */
        NOT_FOUND,
        /** The bank holds a payout a person confirmed was never booked. */
        PAID_AFTER_CANCEL,
        /**
         * No answer: the bank, or the way to it, is down (a failed login, a timeout, a 5xx). Nothing changed, and every
         * payout behind it would most likely end the same way.
         */
        UNANSWERED,
        /**
         * The bank answered, about this loan, nothing that can be concluded from (a bare 404, an HTML page, an unknown
         * code): nothing changed.
         */
        UNREADABLE,
        /** The inquiry itself broke: nothing changed. */
        ERROR,
        /** The loan could not be read, or what the bank said could not be saved: nothing changed. */
        NOT_RECORDED,
        /** Nothing to ask about: the payout is not one the bank's inquiry settles (any more). */
        SKIPPED;

        /**
         * Whether a run may go on to the next payout: not once the bank cannot be reached or the inquiry broke, which
         * would end the same way for every payout behind it and hold the one scheduler thread. An answer about one
         * loan that says nothing, or one that could not be saved, is that loan's alone: the run goes on, and the next
         * run asks again.
         */
        public boolean carriesOn() {
            return this != UNANSWERED && this != ERROR;
        }

        /** Whether the bank's inquiry gave nothing to go on: what a person asking at once is answered 502 for. */
        public boolean unanswered() {
            return this == UNANSWERED || this == UNREADABLE || this == ERROR;
        }
    }

    /** A payout held IN_DOUBT because its claim was never settled, and when it was claimed. */
    private record Abandoned(StaffLoan loan, LocalDateTime claimedAt) {
    }

    /** What a settle decided, to count and audit once it has committed. */
    private record Settled(Checked checked, List<AuditLog.AuditLogBuilder> audits,
                           List<StaffLoanPayoutMetrics.Event> counted) {
        static Settled skipped() {
            return new Settled(Checked.SKIPPED, List.of(), List.of());
        }
    }

    private final StaffLoanRepository loanRepository;
    private final StaffMemberRepository memberRepository;
    private final StaffLoanService loanService;
    private final StaffNotificationService notificationService;
    private final InnbucksLoanInquiry inquiry;
    private final StaffLoanProperties properties;
    private final StaffLoanPayoutMetrics metrics;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;
    private final TransactionTemplate transactionTemplate;

    public StaffLoanPayoutStatusService(StaffLoanRepository loanRepository, StaffMemberRepository memberRepository,
                                        StaffLoanService loanService, StaffNotificationService notificationService,
                                        InnbucksLoanInquiry inquiry, StaffLoanProperties properties,
                                        StaffLoanPayoutMetrics metrics, AuditService auditService,
                                        MarketTimeZone marketTimeZone, PlatformTransactionManager transactionManager) {
        this.loanRepository = loanRepository;
        this.memberRepository = memberRepository;
        this.loanService = loanService;
        this.notificationService = notificationService;
        this.inquiry = inquiry;
        this.properties = properties;
        this.metrics = metrics;
        this.auditService = auditService;
        this.marketTimeZone = marketTimeZone;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Each loan's record commits or rolls back on its own, whoever calls.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Holds every payout claimed more than {@code stale-claim-minutes} before {@code now} and never settled, a chunk
     * at a time, each in its own transaction; one that cannot be held is left for the next run.
     *
     * @return how many were held
     */
    public int holdAbandonedClaims(LocalDateTime now) {
        LocalDateTime cutoff = staleCutoff(now);
        AtomicInteger held = new AtomicInteger();
        IdChunks.forEach((after, chunk) -> loanRepository.findPayoutClaimedBeforeIds(cutoff, after, chunk), ids -> {
            for (Long id : ids) {
                try {
                    if (holdIfAbandoned(id, now)) {
                        held.incrementAndGet();
                    }
                } catch (RuntimeException ex) {
                    log.error("Could not hold staff loan {}, whose payout claim is stale; it stays SENDING", id, ex);
                }
            }
            return true;
        });
        return held.get();
    }

    /**
     * Holds one payout IN_DOUBT if it was claimed more than {@code stale-claim-minutes} before {@code now} and never
     * settled: the process that sent it died mid-call, so it may have been booked. It is never sent again; the bank's
     * inquiry settles it.
     *
     * @return whether it was held
     */
    public boolean holdIfAbandoned(Long staffLoanId, LocalDateTime now) {
        LocalDateTime cutoff = staleCutoff(now);
        Optional<Abandoned> abandoned = transactionTemplate.execute(status -> loanRepository.lockById(staffLoanId)
                .filter(loan -> loan.getPayoutMethod() == StaffLoanPayoutMethod.CASH
                        && loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT
                        && loan.getPayoutStatus() == StaffLoanPayoutStatus.SENDING
                        && loan.getPayoutClaimedAt() != null && loan.getPayoutClaimedAt().isBefore(cutoff))
                .map(loan -> {
                    LocalDateTime claimedAt = loan.getPayoutClaimedAt();
                    if (loan.getPayoutBankSeenAt() != null) {
                        // Its 2xx was recorded as seen by the bank when the settle itself could not be saved.
                        loan.payoutBooked(now, loan.getPayoutApplyAnswer() != null ? loan.getPayoutApplyAnswer()
                                : loan.getPayoutLastAnswer());
                        loan.notePayout("A payout sent at " + marketTimeZone.render(claimedAt) + " was taken by the"
                                + " bank, and that was never settled on the loan. BOOKED; the bank's inquiry says when"
                                + " it is paid.");
                    } else {
                        loan.payoutInDoubt("A payout sent at " + marketTimeZone.render(claimedAt) + " was never"
                                + " recorded, so it may have been booked. Held for the bank's inquiry; it is never"
                                + " sent again.", loan.getPayoutLastAnswer());
                    }
                    loanRepository.save(loan);
                    return new Abandoned(loan, claimedAt);
                }));
        if (abandoned.isEmpty()) {
            return false;
        }
        Abandoned held = abandoned.get();
        metrics.count(StaffLoanPayoutMetrics.Event.ABANDONED);
        log.error("STAFF LOAN PAYOUT CLAIM ABANDONED: {} was claimed for its payout at {} and never settled. The bank"
                + " may have booked and paid it, so it is held {} for its inquiry and never sent again (audited)",
                held.loan().getReference(), held.claimedAt(), held.loan().getPayoutStatus());
        audit(CLAIM_ABANDONED, held.loan(), SYSTEM_ACTOR, "system", "claimedAt:" + held.claimedAt() + ";payout:"
                + held.loan().getPayoutStatus());
        return true;
    }

    /** {@link #check(Long, String)} by the status job. */
    public Checked check(Long staffLoanId) {
        return check(staffLoanId, SYSTEM_ACTOR);
    }

    /**
     * Asks the bank about one payout it took (BOOKED), may have taken (IN_DOUBT), or was confirmed never to have
     * booked and is still watched, and records what it says. The inquiry is made with no transaction open; it never
     * sends a booking, and an answer never fails a payout by itself.
     *
     * @param actor who asked: the status job, or the operator who pressed "check now"
     * @throws IllegalStateException called with a transaction open
     */
    public Checked check(Long staffLoanId, String actor) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Staff loan " + staffLoanId + ": the bank is never asked inside a"
                    + " transaction");
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        String reference;
        try {
            reference = loanRepository.findById(staffLoanId)
                    .filter(loan -> asksAbout(loan, now))
                    .map(StaffLoan::getReference)
                    .orElse(null);
        } catch (RuntimeException ex) {
            metrics.count(StaffLoanPayoutMetrics.Event.NOT_RECORDED);
            log.error("Staff loan {} could not be read to ask the bank about its payout", staffLoanId, ex);
            return Checked.NOT_RECORDED;
        }
        if (reference == null) {
            return Checked.SKIPPED;
        }
        InquiryAnswer answer;
        try {
            answer = inquiry.inquire(reference);
        } catch (RuntimeException ex) {
            metrics.count(StaffLoanPayoutMetrics.Event.INQUIRY_ERROR);
            log.error("The bank's inquiry about staff loan payout {} broke", reference, ex);
            return Checked.ERROR;
        }
        if (answer == null) {
            metrics.count(StaffLoanPayoutMetrics.Event.INQUIRY_ERROR);
            log.error("The bank's inquiry about staff loan payout {} returned nothing", reference);
            return Checked.ERROR;
        }
        if (answer.kind() == InquiryAnswer.Kind.UNANSWERED) {
            metrics.count(StaffLoanPayoutMetrics.Event.INQUIRY_UNANSWERED);
            boolean down = answer.bankUnreachable();
            log.warn("The bank's inquiry about staff loan payout {} said nothing to go on ({}{}); nothing changed",
                    reference, answer, down ? ", the bank cannot be reached" : "");
            return down ? Checked.UNANSWERED : Checked.UNREADABLE;
        }
        Settled settled;
        try {
            settled = transactionTemplate.execute(status -> settle(staffLoanId, answer, actor));
        } catch (RuntimeException ex) {
            metrics.count(StaffLoanPayoutMetrics.Event.NOT_RECORDED);
            log.error("STAFF LOAN PAYOUT STATUS NOT RECORDED: the bank answered about {} ({}) and it could not be"
                    + " saved; nothing changed, and the next check asks again", reference, answer, ex);
            return Checked.NOT_RECORDED;
        }
        settled.counted().forEach(metrics::count);
        settled.audits().forEach(this::audit);
        return settled.checked();
    }

    /**
     * A person's "check now" for one payout: what the status job does for it, at once. A claim left unsettled for
     * {@code stale-claim-minutes} is held IN_DOUBT first (never sent again), then the bank is asked, with no
     * transaction open, and what it says is recorded as the job would record it.
     *
     * @param actor who asked
     * @return what the inquiry came to; SKIPPED when the payout was settled by someone else meanwhile
     * @throws NotFoundException                   no such loan
     * @throws ConflictException                   not a payout the bank's inquiry settles: a voucher loan, a payout
     *                                             READY, REFUSED or settled, or one being sent right now
     * @throws StaffLoanPayoutUnansweredException  the bank's inquiry did not answer, said nothing to go on, or broke:
     *                                             nothing changed
     * @throws StaffLoanPayoutNotRecordedException the bank answered, and what it said could not be saved: nothing
     *                                             changed
     * @throws IllegalStateException               called with a transaction open
     */
    public Checked checkNow(Long staffLoanId, String actor) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Staff loan " + staffLoanId + ": the bank is never asked inside a"
                    + " transaction");
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        StaffLoan loan = loanRepository.findById(staffLoanId).orElseThrow(() -> notFound(staffLoanId));
        if (loan.getPayoutMethod() != StaffLoanPayoutMethod.CASH) {
            throw new ConflictException("Staff loan " + loan.getReference() + " is a voucher loan: it has no payout for"
                    + " the bank to report on");
        }
        if (loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT
                && loan.getPayoutStatus() == StaffLoanPayoutStatus.SENDING) {
            if (!holdIfAbandoned(staffLoanId, now)) {
                LocalDateTime claimedAt = loan.getPayoutClaimedAt();
                throw new ConflictException("Staff loan " + loan.getReference() + " is being sent to the bank right now"
                        + (claimedAt == null ? "" : " (since " + marketTimeZone.render(claimedAt) + ")")
                        + "; its status can be checked once that send is settled"
                        + (claimedAt == null ? "" : ", or from " + marketTimeZone.render(claimedAt.plusMinutes(
                        properties.getPayout().getStaleClaimMinutes())) + " if it never is"));
            }
        } else if (!asksAbout(loan, now)) {
            throw new ConflictException("Staff loan " + loan.getReference() + " has " + describe(loan) + "; only a"
                    + " payout the bank took (BOOKED) or may have taken (IN_DOUBT), or one recorded as never booked"
                    + " in the last " + properties.getPayout().getFailedWatch().toDays() + " days, can be checked");
        }
        Checked checked = check(staffLoanId, actor);
        if (checked == Checked.NOT_RECORDED) {
            throw new StaffLoanPayoutNotRecordedException("What the bank's inquiry said about staff loan "
                    + loan.getReference() + " could not be saved; nothing changed. Try again in a minute.");
        }
        if (checked.unanswered()) {
            throw new StaffLoanPayoutUnansweredException("The bank's inquiry about staff loan " + loan.getReference()
                    + " gave no answer to go on (" + checked + "); nothing changed. Try again later.");
        }
        return checked;
    }

    /**
     * Records that the bank paid a payout it took or may have taken, as a person who asked it confirms: PAID, the loan
     * DISBURSED, and the borrower told.
     *
     * @param bankReference the bank's reference for the payment, as it gave it
     * @param note          what the bank said, by whom; kept as its hash
     * @throws NotFoundException     no such loan
     * @throws ConflictException     not a cash payout BOOKED or IN_DOUBT
     * @throws AccessDeniedException {@code user} is a party to the loan
     * @throws ValidationException   no bank reference, or no note
     */
    @Transactional
    public void confirmPaid(Long staffLoanId, String bankReference, String note, User user) {
        String bankRef = required(bankReference, "bankReference");
        if (bankRef.length() > BANK_REFERENCE_LENGTH) {
            throw new ValidationException("bankReference must be at most " + BANK_REFERENCE_LENGTH + " characters");
        }
        String why = required(note, "note");
        // The register before the loan, the order every register writer takes them in: a borrower who leaves as this
        // commits is flagged either here or by the register approval, never by neither.
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        StaffLoan loan = loanRepository.lockById(staffLoanId).orElseThrow(() -> notFound(staffLoanId));
        if (loan.getPayoutMethod() != StaffLoanPayoutMethod.CASH
                || loan.getStatus() != StaffLoanStatus.AWAITING_DISBURSEMENT
                || loan.getPayoutStatus() == null || !loan.getPayoutStatus().awaitsInquiry()) {
            throw new ConflictException("Staff loan " + loan.getReference() + " has " + describe(loan)
                    + "; only a payout the bank took (BOOKED) or may have taken (IN_DOUBT) can be confirmed paid");
        }
        StaffMember borrower = memberRepository.findById(loan.getStaffMemberId()).orElse(null);
        String by = requireNotParty(loan, borrower, user, "confirm its payout paid");
        StaffLoanPayoutStatus was = loan.getPayoutStatus();
        LocalDateTime now = marketTimeZone.nowUtc();
        // The bank holds it and paid it: a person has its word for it.
        loan.paidOut(now, null);
        loanRepository.save(loan);
        notifyPaid(loan);
        flagIfNoLongerActive(loan, borrower, by);
        metrics.count(StaffLoanPayoutMetrics.Event.CONFIRMED_PAID);
        log.warn("Staff loan {} recorded as paid by {} on the bank's word (was {}, bank reference {}) (audited)",
                loan.getReference(), by, was, bankRef);
        audit(entry(CONFIRMED_PAID, loan, by, PORTAL_CHANNEL, "was:" + was + ";bankReference:" + bankRef)
                .payloadHash(AuditService.sha256Hex(why)));
        audit(entry(PAID_OUT, loan, by, PORTAL_CHANNEL, paidDetail(loan) + ";via:operator"));
    }

    /**
     * Records that the bank never paid a payout, as a person who asked it confirms: FAILED, the loan cancelled, and the
     * borrower told. Only for a payout the bank's inquiry has said, for at least {@code not-booked-min-age} and still
     * lately (its last answer no older than twice {@code status-check-interval}), either holds no loan under the
     * reference (an IN_DOUBT payout the bank has never said it holds, and whose booking it never answered 2xx), or
     * holds it in a status that says it declined it ({@code failed-statuses}): a wrong confirmation frees the borrower
     * to borrow, and be paid, again. The payout is still watched after, in case the bank pays it after all.
     *
     * @param note what the bank said, by whom; kept as its hash
     * @throws NotFoundException     no such loan
     * @throws ConflictException     not such a payout
     * @throws AccessDeniedException {@code user} is a party to the loan
     * @throws ValidationException   no note
     */
    @Transactional
    public void confirmNotBooked(Long staffLoanId, String note, User user) {
        String why = required(note, "note");
        StaffLoan loan = loanRepository.lockById(staffLoanId).orElseThrow(() -> notFound(staffLoanId));
        LocalDateTime now = marketTimeZone.nowUtc();
        LocalDateTime evidenceBy = now.minus(properties.getPayout().getNotBookedMinAge());
        LocalDateTime answeredSince = answeredSince(now);
        String refusal = notBookedRefusal(loan, evidenceBy, answeredSince);
        if (refusal != null) {
            throw new ConflictException("Staff loan " + loan.getReference() + " cannot be recorded as never booked: "
                    + refusal);
        }
        StaffMember borrower = memberRepository.findById(loan.getStaffMemberId()).orElse(null);
        String by = requireNotParty(loan, borrower, user, "record its payout as never booked");
        boolean declined = loan.reportedDeclinedSince(evidenceBy);
        String evidence = declined
                ? "declinedSince:" + loan.getPayoutDeclinedAt() + ";bankStatus:" + loan.getPayoutBankStatus()
                : "notFoundSince:" + loan.getPayoutNotFoundAt();
        String reason = declined
                ? "The bank confirmed this loan's payout was never paid; it holds it as " + loan.getPayoutBankStatus()
                + " (recorded by " + by + ")"
                : "The bank confirmed this loan's payout was never booked (recorded by " + by + ")";
        loan.payoutNotBooked(now, evidenceBy, answeredSince, by, reason);
        loanRepository.save(loan);
        notificationService.notifyLoan(loan, StaffNotificationTemplate.LOAN_NOT_PAID_OUT,
                StaffNotificationTemplate.LOAN_NOT_PAID_OUT.notPaidOutText(loan.getReference()));
        metrics.count(StaffLoanPayoutMetrics.Event.CONFIRMED_NOT_BOOKED);
        log.warn("Staff loan {}: its payout recorded as never paid by {} ({}); the loan is cancelled and the borrower"
                + " may borrow again (audited)", loan.getReference(), by, evidence);
        audit(entry(CONFIRMED_NOT_BOOKED, loan, by, PORTAL_CHANNEL, evidence).payloadHash(AuditService.sha256Hex(why)));
        audit(entry(StaffLoanService.CANCELLED, loan, by, PORTAL_CHANNEL, "reason:" + reason));
    }

    /** Why this loan's payout cannot be recorded as never booked, or null when it can. */
    private String notBookedRefusal(StaffLoan loan, LocalDateTime evidenceBy, LocalDateTime answeredSince) {
        if (loan.getPayoutMethod() != StaffLoanPayoutMethod.CASH
                || loan.getStatus() != StaffLoanStatus.AWAITING_DISBURSEMENT) {
            return "it has " + describe(loan) + "; only a cash payout BOOKED or IN_DOUBT can be";
        }
        if (loan.getPayoutStatus() == null || !loan.getPayoutStatus().awaitsInquiry()) {
            return "its payout is " + loan.getPayoutStatus() + "; only one BOOKED or IN_DOUBT can be";
        }
        String stale = staleAnswer(loan, answeredSince);
        if (loan.getPayoutDeclinedAt() != null) {
            if (loan.getPayoutDeclinedAt().isAfter(evidenceBy)) {
                return "the bank has held it as " + loan.getPayoutBankStatus() + " only since "
                        + marketTimeZone.render(loan.getPayoutDeclinedAt()) + ", and that may still change; it can be"
                        + " from " + marketTimeZone.render(loan.getPayoutDeclinedAt()
                        .plus(properties.getPayout().getNotBookedMinAge()));
            }
            return stale;
        }
        if (loan.getPayoutStatus() == StaffLoanPayoutStatus.BOOKED) {
            return "the bank took its payout (BOOKED), so it was booked; only a payout the bank holds in a declined"
                    + " status (" + String.join(", ", properties.getPayout().getFailedStatuses()) + ") can be";
        }
        if (loan.getPayoutBankSeenAt() != null) {
            return "the bank has said it holds it (since " + marketTimeZone.render(loan.getPayoutBankSeenAt()) + ")";
        }
        if (bookingAnswered2xx(loan)) {
            return "the bank answered its booking with a 2xx (audited), so it may have booked it";
        }
        if (loan.getPayoutNotFoundAt() == null) {
            return "the bank's inquiry has never reported it missing; check its status first";
        }
        if (loan.getPayoutNotFoundAt().isAfter(evidenceBy)) {
            return "the bank's inquiry has reported it missing only since "
                    + marketTimeZone.render(loan.getPayoutNotFoundAt()) + ", and a booking may still be on its way;"
                    + " it can be from " + marketTimeZone.render(loan.getPayoutNotFoundAt()
                    .plus(properties.getPayout().getNotBookedMinAge()));
        }
        return stale;
    }

    /** Why the bank's last answer is too old to go on, or null when it answered at or after {@code answeredSince}. */
    private String staleAnswer(StaffLoan loan, LocalDateTime answeredSince) {
        if (loan.getPayoutCheckedAt() != null && !loan.getPayoutCheckedAt().isBefore(answeredSince)) {
            return null;
        }
        return (loan.getPayoutCheckedAt() == null ? "the bank's inquiry has not answered about it"
                : "the bank's inquiry last answered at " + marketTimeZone.render(loan.getPayoutCheckedAt())
                + ", so it may have changed since") + "; check its status now, then record it within "
                + freshFor().toMinutes() + " minutes";
    }

    /**
     * Whether the bank answered a booking of this payout with a 2xx that could not be recorded on the loan: audited, and
     * enough to refuse "never booked" on.
     */
    private boolean bookingAnswered2xx(StaffLoan loan) {
        String id = String.valueOf(loan.getId());
        return auditService.hasRecorded(StaffLoanPayoutService.BOOKED_NOT_RECORDED, "STAFF_LOAN", id)
                || auditService.hasRecorded(StaffLoanPayoutService.BOOKED, "STAFF_LOAN", id);
    }

    /** The oldest last answer "never booked" may still go on: twice {@code status-check-interval} ago. */
    private LocalDateTime answeredSince(LocalDateTime now) {
        return now.minus(freshFor());
    }

    private Duration freshFor() {
        return properties.getPayout().getStatusCheckInterval().multipliedBy(2);
    }

    /** Whether the bank's inquiry is asked about this loan now. */
    private boolean asksAbout(StaffLoan loan, LocalDateTime now) {
        if (loan.getPayoutMethod() != StaffLoanPayoutMethod.CASH || loan.getPayoutStatus() == null) {
            return false;
        }
        if (loan.getPayoutStatus() == StaffLoanPayoutStatus.FAILED) {
            return watched(loan, now);
        }
        return loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT && loan.getPayoutStatus().awaitsInquiry();
    }

    /** A payout confirmed never booked, within {@code failed-watch}, the bank not yet found holding it. */
    private boolean watched(StaffLoan loan, LocalDateTime now) {
        return loan.getPayoutStatus() == StaffLoanPayoutStatus.FAILED && loan.getPayoutPaidAfterCancelAt() == null
                && loan.getPayoutFailedAt() != null
                && !loan.getPayoutFailedAt().isBefore(now.minus(properties.getPayout().getFailedWatch()));
    }

    private Settled settle(Long staffLoanId, InquiryAnswer answer, String actor) {
        if (answer.kind() == InquiryAnswer.Kind.FOUND) {
            // A FOUND answer may pay the loan out, and a loan paid to a borrower who left is flagged: the register
            // first, as every register writer takes it, so a register approval and this never interleave.
            memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        }
        StaffLoan loan = loanRepository.lockById(staffLoanId).orElse(null);
        LocalDateTime now = marketTimeZone.nowUtc();
        if (loan == null || !asksAbout(loan, now)) {
            // Settled by someone else between the read and the lock.
            return Settled.skipped();
        }
        if (loan.getPayoutStatus() == StaffLoanPayoutStatus.FAILED) {
            return watchedAnswer(loan, answer, now, actor);
        }
        return answer.kind() == InquiryAnswer.Kind.NOT_FOUND ? notFound(loan, answer, now, actor)
                : found(loan, answer, now, actor);
    }

    private Settled notFound(StaffLoan loan, InquiryAnswer answer, LocalDateTime now, String actor) {
        boolean first = loan.payoutNotFound(now, answer.raw());
        List<AuditLog.AuditLogBuilder> audits = new ArrayList<>();
        List<StaffLoanPayoutMetrics.Event> counted = new ArrayList<>();
        if (first) {
            loan.notePayout(loan.getPayoutStatus() == StaffLoanPayoutStatus.BOOKED
                    ? "The bank took this payout, yet its inquiry now reports no loan under " + loan.getReference()
                    + ". Ask the bank."
                    : "The bank's inquiry reports no loan under " + loan.getReference() + ", so it may never have"
                    + " been booked. Ask the bank; from " + marketTimeZone.render(
                    now.plus(properties.getPayout().getNotBookedMinAge())) + " a SUPER_ADMIN may record it as not"
                    + " booked if the bank confirms it.");
            log.error("STAFF LOAN PAYOUT NOT FOUND: the bank's inquiry reports no loan under {} (payout {}); it is"
                    + " never failed for that alone, and stays held for a person (audited)", loan.getReference(),
                    loan.getPayoutStatus());
            audits.add(entry(NOT_FOUND, loan, actor, channel(actor), "payout:" + loan.getPayoutStatus()
                    + ";responseCode:" + answer.responseCode() + ";http:" + answer.httpStatus()));
            counted.add(StaffLoanPayoutMetrics.Event.NOT_FOUND);
        }
        loanRepository.save(loan);
        return new Settled(Checked.NOT_FOUND, audits, counted);
    }

    private Settled found(StaffLoan loan, InquiryAnswer answer, LocalDateTime now, String actor) {
        StaffLoanProperties.Payout settings = properties.getPayout();
        String bankStatus = answer.bankStatus();
        boolean wasNotFound = loan.getPayoutNotFoundAt() != null;
        StaffLoanPayoutStatus was = loan.getPayoutStatus();
        List<AuditLog.AuditLogBuilder> audits = new ArrayList<>();
        List<StaffLoanPayoutMetrics.Event> counted = new ArrayList<>();
        // Whatever it says, the bank holds it: its status and answer are kept, and it is seen by the bank for good. A
        // declined status is also dated, from the first time the bank said so.
        boolean declined = settings.isFailedStatus(bankStatus);
        boolean firstDeclined = declined && loan.payoutDeclined(now, bankStatus, answer.raw());
        if (!declined) {
            loan.payoutFound(now, bankStatus, answer.raw());
        }
        if (wasNotFound) {
            audits.add(entry(FOUND, loan, actor, channel(actor), "bankStatus:" + bankStatus));
        }
        Checked checked;
        if (settings.isPaidStatus(bankStatus)) {
            List<String> mismatches = echoMismatches(loan, answer);
            if (!mismatches.isEmpty()) {
                loan.notePayout("The bank reports it paid (" + bankStatus + "), but its answer names a different "
                        + String.join(" and ", mismatches) + ", so it is not recorded as paid. Confirm with the bank,"
                        + " then record it with Confirm paid.");
                log.error("STAFF LOAN PAYOUT ECHO MISMATCH: the bank reports {} paid, but its answer names a different"
                        + " {}; held, not recorded as paid (audited)", loan.getReference(), mismatches);
                if (!auditService.hasRecorded(ECHO_MISMATCH, "STAFF_LOAN", String.valueOf(loan.getId()))) {
                    audits.add(entry(ECHO_MISMATCH, loan, actor, channel(actor), "bankStatus:" + bankStatus
                            + ";mismatch:" + String.join(",", mismatches)));
                }
                counted.add(StaffLoanPayoutMetrics.Event.ECHO_MISMATCH);
                checked = Checked.ECHO_MISMATCH;
            } else {
                loan.paidOut(now, answer.bankLoanAccount());
                notifyPaid(loan);
                StaffMember borrower = memberRepository.findById(loan.getStaffMemberId()).orElse(null);
                flagIfNoLongerActive(loan, borrower, actor);
                log.info("Staff loan {} paid out by the bank ({}), was {}", loan.getReference(), bankStatus, was);
                audits.add(entry(PAID_OUT, loan, actor, channel(actor), paidDetail(loan) + ";bankStatus:" + bankStatus
                        + ";via:inquiry"));
                counted.add(StaffLoanPayoutMetrics.Event.PAID);
                if (answer.interestRate() != null && answer.interestRate().signum() != 0) {
                    log.error("STAFF LOAN PAYOUT INTEREST CHARGED: the bank booked {} at an interest rate of {}; the"
                            + " loan carries none (audited)", loan.getReference(), answer.interestRate());
                    audits.add(entry(INTEREST_CHARGED, loan, actor, channel(actor), "interestRate:"
                            + answer.interestRate().toPlainString()));
                    counted.add(StaffLoanPayoutMetrics.Event.INTEREST_CHARGED);
                }
                checked = Checked.PAID;
            }
        } else if (settings.isPendingStatus(bankStatus)) {
            loan.notePayout(null);
            checked = Checked.BOOKED;
        } else if (declined) {
            if (firstDeclined) {
                log.error("STAFF LOAN PAYOUT DECLINED: the bank holds {} as {}, a status that says it did not pay it;"
                        + " held for a person (audited)", loan.getReference(), bankStatus);
                audits.add(entry(DECLINED, loan, actor, channel(actor), "bankStatus:" + bankStatus));
                counted.add(StaffLoanPayoutMetrics.Event.DECLINED);
            }
            loan.notePayout("The bank holds this payout as " + bankStatus + ", which says it did not pay it. Ask the"
                    + " bank; from " + marketTimeZone.render(loan.getPayoutDeclinedAt().plus(
                    settings.getNotBookedMinAge())) + " a SUPER_ADMIN may record it as not booked if the bank confirms"
                    + " it.");
            checked = Checked.DECLINED;
        } else {
            loan.notePayout("The bank holds it in a status this service does not know (" + bankStatus + "), so it is"
                    + " not recorded as paid. Ask the bank what it means.");
            log.warn("Staff loan {}: the bank holds its payout in status {}, which is neither paid ({}) nor pending"
                    + " ({})", loan.getReference(), bankStatus, settings.getPaidStatuses(),
                    settings.getPendingStatuses());
            if (!auditService.hasRecorded(UNKNOWN_BANK_STATUS, "STAFF_LOAN", String.valueOf(loan.getId()))) {
                audits.add(entry(UNKNOWN_BANK_STATUS, loan, actor, channel(actor), "bankStatus:" + bankStatus));
            }
            counted.add(StaffLoanPayoutMetrics.Event.UNKNOWN_BANK_STATUS);
            checked = Checked.UNKNOWN_STATUS;
        }
        loanRepository.save(loan);
        return new Settled(checked, audits, counted);
    }

    private Settled watchedAnswer(StaffLoan loan, InquiryAnswer answer, LocalDateTime now, String actor) {
        if (answer.kind() != InquiryAnswer.Kind.FOUND) {
            return new Settled(Checked.NOT_FOUND, List.of(), List.of());
        }
        if (properties.getPayout().isFailedStatus(answer.bankStatus())) {
            // The bank holds it, declined: no money moved.
            return new Settled(Checked.DECLINED, List.of(), List.of());
        }
        boolean first = loan.payoutPaidAfterCancel(now, answer.bankStatus(), answer.raw());
        loanRepository.save(loan);
        List<AuditLog.AuditLogBuilder> audits = new ArrayList<>();
        List<StaffLoanPayoutMetrics.Event> counted = new ArrayList<>();
        if (first) {
            log.error("STAFF LOAN PAID AFTER CANCEL: the bank holds {} ({}), whose payout was confirmed never booked"
                    + " and whose loan is cancelled. The borrower may have been paid for a cancelled loan; their"
                    + " further payouts are held until it is settled with the bank (audited)", loan.getReference(),
                    answer.bankStatus());
            audits.add(entry(PAID_AFTER_CANCEL, loan, actor, channel(actor), "bankStatus:" + answer.bankStatus()
                    + ";failedAt:" + loan.getPayoutFailedAt()));
            counted.add(StaffLoanPayoutMetrics.Event.PAID_AFTER_CANCEL);
        }
        return new Settled(Checked.PAID_AFTER_CANCEL, audits, counted);
    }

    /**
     * What the bank's "paid" answer names that is not this loan's, for each of these it carries (one it leaves out is
     * not checked): its reference; its amount, which must be the loan's in cents or in major units exactly; and where
     * it paid, read by the payout's destination. A wallet payout pays the borrower's phone, so the echoed
     * {@code msisdn} is the account (by its last nine digits, however the bank writes a phone) and a
     * {@code settlementAccount} is not read. An account payout pays {@code settlementAccount}, compared by its digits,
     * and the echoed {@code msisdn} must be the borrower's phone. One field is never read in place of the other.
     */
    static List<String> echoMismatches(StaffLoan loan, InquiryAnswer answer) {
        List<String> mismatches = new ArrayList<>();
        if (answer.echoedReference() != null && !answer.echoedReference().strip().equals(loan.getReference())) {
            mismatches.add("reference");
        }
        BigDecimal amount = answer.amount();
        if (amount != null && loan.getAmount() != null && amount.compareTo(loan.getAmount()) != 0
                && amount.compareTo(loan.getAmount().movePointRight(2)) != 0) {
            mismatches.add("amount");
        }
        if (loan.getPayoutDestination() == StaffLoanPayoutDestination.BR_ACCOUNT) {
            if (answer.echoedSettlementAccount() != null && !digits(answer.echoedSettlementAccount()).equals(
                    digits(loan.getPayoutAccount()))) {
                mismatches.add("account");
            }
            if (answer.echoedMsisdn() != null && !samePhone(loan.getMsisdn(), answer.echoedMsisdn())) {
                mismatches.add("phone");
            }
        } else if (answer.echoedMsisdn() != null && !samePhone(loan.getPayoutAccount(), answer.echoedMsisdn())) {
            mismatches.add("account");
        }
        return mismatches;
    }

    /** The same Zimbabwean phone, however the bank writes it: the last nine digits. */
    private static boolean samePhone(String ours, String echoed) {
        String mine = digits(ours);
        String theirs = digits(echoed);
        if (mine.equals(theirs)) {
            return true;
        }
        return mine.length() >= 9 && theirs.length() >= 9
                && mine.substring(mine.length() - 9).equals(theirs.substring(theirs.length() - 9));
    }

    private void notifyPaid(StaffLoan loan) {
        notificationService.notifyLoan(loan, StaffNotificationTemplate.LOAN_PAID_OUT,
                StaffNotificationTemplate.LOAN_PAID_OUT.paidOutText(loan.getCurrency(), loan.getAmount(),
                        loan.getPayoutDestination().label(), MsisdnUtils.lastFourDigits(loan.getPayoutAccount()),
                        loan.getDueDate(), loan.getReference()));
    }

    /**
     * A loan paid to a borrower who is no longer ACTIVE: a register approval that moved them while its payout was on
     * its way left it unflagged (the money may not have been out), so it is flagged now.
     */
    private void flagIfNoLongerActive(StaffLoan loan, StaffMember borrower, String actor) {
        if (borrower == null || borrower.getEmploymentStatus() == StaffEmploymentStatus.ACTIVE) {
            return;
        }
        log.warn("Staff loan {} was paid out to {}, who is {} on the register; flagged", loan.getReference(),
                loan.getEmployeeNumber(), borrower.getEmploymentStatus());
        loanService.flagEmploymentAtPayout(loan, borrower.getEmploymentStatus(), actor,
                borrower.getUpdatedBatchId());
    }

    private String requireNotParty(StaffLoan loan, StaffMember borrower, User user, String action) {
        String by = user == null ? null : user.getUsername();
        if (user == null || by == null || by.isBlank()) {
            throw new AccessDeniedException("Nobody is signed in to " + action + " for staff loan "
                    + loan.getReference());
        }
        if (SegregationOfDuties.isPartyTo(loan, borrower, user)) {
            throw new AccessDeniedException(by + " is a party to staff loan " + loan.getReference() + " and cannot "
                    + action + "; someone else must");
        }
        return by;
    }

    private String paidDetail(StaffLoan loan) {
        return "amount:" + loan.getAmount().toPlainString() + ";currency:" + loan.getCurrency() + ";destination:"
                + loan.getPayoutDestination() + ";account:" + MsisdnUtils.mask(loan.getPayoutAccount())
                + (loan.getBankLoanAccount() == null ? ""
                : ";bankLoanAccount:" + StaffLoanResponse.maskLoanAccount(loan.getBankLoanAccount()));
    }

    private LocalDateTime staleCutoff(LocalDateTime now) {
        return now.minusMinutes(properties.getPayout().getStaleClaimMinutes());
    }

    private static String channel(String actor) {
        return SYSTEM_ACTOR.equals(actor) ? "system" : PORTAL_CHANNEL;
    }

    private static AuditLog.AuditLogBuilder entry(String eventType, StaffLoan loan, String actor, String channel,
                                                  String detail) {
        return AuditLog.builder()
                .eventType(eventType)
                .entityType("STAFF_LOAN").entityId(String.valueOf(loan.getId()))
                .actorId(actor).channelUsed(channel)
                .detail("reference:" + loan.getReference() + ";" + detail)
                .correlationId(loan.getReference());
    }

    private void audit(String eventType, StaffLoan loan, String actor, String channel, String detail) {
        audit(entry(eventType, loan, actor, channel, detail));
    }

    private void audit(AuditLog.AuditLogBuilder entry) {
        try {
            auditService.record(entry);
        } catch (RuntimeException ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while opening the
            // transaction; the log line is the evidence.
            log.error("Audit of a staff loan payout failed: {}", entry.build().getEventType(), ex);
        }
    }

    private static String describe(StaffLoan loan) {
        return loan.getPayoutMethod() == StaffLoanPayoutMethod.CASH
                ? "payout " + loan.getPayoutStatus() + " (loan " + loan.getStatus() + ")"
                : "no payout (a voucher loan, " + loan.getStatus() + ")";
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " is required");
        }
        return value.strip();
    }

    private static NotFoundException notFound(Long id) {
        return new NotFoundException("Staff loan " + id + " not found");
    }
}
