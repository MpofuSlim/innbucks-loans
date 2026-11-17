package zw.co.innbucks.loans.core.staff.loan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.jobs.IdChunks;
import zw.co.innbucks.loans.core.loan.SegregationOfDuties;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.user.User;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a person asks of a cash Staff Grocery Loan's payout from the portal, as the signed-in user: send one now, send
 * every READY one, ask the bank about one now, or record what the bank said of one it could not report itself. Nobody
 * acts on a loan they are a party to (the borrower, or whoever holds the wallet it pays): a person who could send
 * their own payout, or record it paid, could pay themselves.
 *
 * <p>Sending is never done here: a payout asked for is queued for {@link StaffLoanPayoutDispatcher}'s one thread once
 * the request's own change has committed, and the answer is the loan as it stood then, READY, read before it was
 * queued (the payout thread may have claimed it by the time the answer is written). A payout
 * the bank took, or may have taken, is never sent again from here: only one READY, or REFUSED by the bank (a refusal
 * proves nothing was booked), can be, under its own reference.
 */
@Slf4j
@Service
public class StaffLoanPayoutRequests {

    static final String REQUESTED = "STAFF_LOAN_PAYOUT_REQUESTED";
    static final String PORTAL_CHANNEL = "admin-portal";

    private final StaffLoanRepository loanRepository;
    private final StaffMemberRepository memberRepository;
    private final StaffLoanService loanService;
    private final StaffLoanPayoutDispatcher dispatcher;
    private final StaffLoanPayoutStatusService statusService;
    private final AuthService authService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public StaffLoanPayoutRequests(StaffLoanRepository loanRepository, StaffMemberRepository memberRepository,
                                   StaffLoanService loanService, StaffLoanPayoutDispatcher dispatcher,
                                   StaffLoanPayoutStatusService statusService, AuthService authService,
                                   AuditService auditService, PlatformTransactionManager transactionManager) {
        this.loanRepository = loanRepository;
        this.memberRepository = memberRepository;
        this.loanService = loanService;
        this.dispatcher = dispatcher;
        this.statusService = statusService;
        this.authService = authService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * "Pay out": queues one cash payout READY to send, or REFUSED by the bank (READY again, under the same reference),
     * once that change has committed.
     *
     * @return the loan as it stood when it was queued: READY, read in the transaction that made it so
     * @throws NotFoundException                 no such loan
     * @throws ConflictException                 not a cash payout READY or REFUSED
     * @throws AccessDeniedException             the signed-in user is a party to the loan
     * @throws StaffLoanPayoutQueueFullException the payout queue had no room: the loan is READY, and nothing was sent
     * @throws IllegalStateException             called with a transaction open (it would queue before the commit)
     */
    public StaffLoanResponse sendNow(Long staffLoanId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Staff loan " + staffLoanId + ": a payout is queued only once its own"
                    + " change has committed");
        }
        User user = authService.getLoggedInUser();
        Asked asked = transactionTemplate.execute(status -> readyToSend(staffLoanId, user));
        boolean queued = dispatcher.request(staffLoanId);
        log.info("Staff loan {}: payout asked for by {} (was {}){}", asked.reference(), asked.by(), asked.was(),
                queued ? "" : "; the queue was full, so it was not queued");
        audit(AuditLog.builder()
                .eventType(REQUESTED)
                .entityType("STAFF_LOAN").entityId(String.valueOf(staffLoanId))
                .actorId(asked.by()).channelUsed(PORTAL_CHANNEL)
                .detail("reference:" + asked.reference() + ";was:" + asked.was() + ";queued:" + queued)
                .correlationId(asked.reference()));
        if (!queued) {
            throw new StaffLoanPayoutQueueFullException("The payout queue is full, so staff loan " + asked.reference()
                    + " was not queued; it is READY, and nothing was sent. Try again in a few minutes.");
        }
        return asked.loan();
    }

    /** Who asked for a payout, where it stood, and the loan as it then stands. */
    private record Asked(String reference, StaffLoanPayoutStatus was, String by, StaffLoanResponse loan) {
    }

    /** Makes a READY or REFUSED payout READY to send, under the loan's row lock, unless the caller may not send it. */
    private Asked readyToSend(Long staffLoanId, User user) {
        StaffLoan loan = loanRepository.lockById(staffLoanId).orElseThrow(() -> notFound(staffLoanId));
        if (loan.getPayoutMethod() != StaffLoanPayoutMethod.CASH) {
            throw new ConflictException("Staff loan " + loan.getReference() + " is a voucher loan: it is not paid out"
                    + " in cash");
        }
        StaffLoanPayoutStatus was = loan.getPayoutStatus();
        if (loan.getStatus() != StaffLoanStatus.AWAITING_DISBURSEMENT || was == null || !was.cancellable()) {
            throw new ConflictException("Staff loan " + loan.getReference() + " has payout " + was + " (loan "
                    + loan.getStatus() + "); only a payout READY to send, or REFUSED by the bank, can be sent"
                    + (was != null && was.inFlight() ? ". The bank took it or may have taken it, so it is never sent"
                    + " again: check its status instead" : ""));
        }
        StaffMember borrower = memberRepository.findById(loan.getStaffMemberId()).orElse(null);
        String by = requireNotParty(loan, borrower, user, "send its payout");
        if (was == StaffLoanPayoutStatus.REFUSED) {
            loan.sendRefusedPayoutAgain();
            loanRepository.save(loan);
        }
        // Read here, before it is queued: once queued, the payout thread may claim it before the answer is written.
        return new Asked(loan.getReference(), was, by, loanService.summary(staffLoanId));
    }

    /**
     * "Pay all ready": queues one pass over every cash payout READY to send, oldest first, leaving out those the
     * signed-in user is a party to. The pass stops at the first payout that is not sent, refused or in doubt: every one
     * behind it would most likely end the same way. One not sent for a reason of its own (a 403 to that one request, a
     * booking that could not be built) stays READY and the pass goes on. It is how payouts left READY by a restart are
     * sent: there is no sweep at boot.
     *
     * @throws StaffLoanPayoutQueueFullException the payout queue had no room: nothing was queued
     */
    public StaffLoanPayoutSweepResponse payAllReady() {
        User user = authService.getLoggedInUser();
        Set<Long> own = new HashSet<>();
        AtomicLong ready = new AtomicLong();
        IdChunks.forEach(loanRepository::findPayoutReadyIds, ids -> {
            List<StaffLoan> loans = loanRepository.findAllById(ids);
            Map<Long, StaffMember> borrowers = memberRepository.findAllById(loans.stream()
                            .map(StaffLoan::getStaffMemberId).distinct().toList()).stream()
                    .collect(Collectors.toMap(StaffMember::getId, Function.identity()));
            for (StaffLoan loan : loans) {
                if (SegregationOfDuties.isPartyTo(loan, borrowers.get(loan.getStaffMemberId()), user)) {
                    own.add(loan.getId());
                } else {
                    ready.incrementAndGet();
                }
            }
            return true;
        });
        String by = user.getUsername();
        if (!dispatcher.requestSweep(own)) {
            throw new StaffLoanPayoutQueueFullException("The payout queue is full, so the " + ready.get() + " ready"
                    + " payout(s) were not queued; they are READY, and nothing was sent. Try again in a few minutes.");
        }
        if (!own.isEmpty()) {
            log.warn("{} asked to pay every ready staff loan; {} of them left out as theirs: someone else must send"
                    + " those", by, own.size());
        }
        log.info("Staff loan payouts: a pass over {} ready payout(s) queued by {}", ready.get(), by);
        audit(AuditLog.builder()
                .eventType(REQUESTED)
                .entityType("STAFF_LOAN")
                .actorId(by).channelUsed(PORTAL_CHANNEL)
                .detail("sweep:ready=" + ready.get() + ";skippedOwn=" + own.size()));
        return new StaffLoanPayoutSweepResponse(ready.get(), own.size());
    }

    /**
     * "Check now": asks the bank about one payout at once, as the signed-in user ({@link
     * StaffLoanPayoutStatusService#checkNow}).
     */
    public StaffLoanPayoutStatusService.Checked checkNow(Long staffLoanId) {
        return statusService.checkNow(staffLoanId, authService.getLoggedInUsername());
    }

    /**
     * Records, as the signed-in user, that the bank paid a payout ({@link StaffLoanPayoutStatusService#confirmPaid}).
     */
    public void confirmPaid(Long staffLoanId, String bankReference, String note) {
        statusService.confirmPaid(staffLoanId, bankReference, note, authService.getLoggedInUser());
    }

    /**
     * Records, as the signed-in user, that the bank never booked a payout
     * ({@link StaffLoanPayoutStatusService#confirmNotBooked}).
     */
    public void confirmNotBooked(Long staffLoanId, String note) {
        statusService.confirmNotBooked(staffLoanId, note, authService.getLoggedInUser());
    }

    private static String requireNotParty(StaffLoan loan, StaffMember borrower, User user, String action) {
        String by = user == null ? null : user.getUsername();
        if (by == null || by.isBlank()) {
            throw new AccessDeniedException("Nobody is signed in to " + action + " for staff loan "
                    + loan.getReference());
        }
        if (SegregationOfDuties.isPartyTo(loan, borrower, user)) {
            throw new AccessDeniedException(by + " is a party to staff loan " + loan.getReference() + " and cannot "
                    + action + "; someone else must");
        }
        return by;
    }

    private void audit(AuditLog.AuditLogBuilder entry) {
        try {
            auditService.record(entry);
        } catch (RuntimeException ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while opening the
            // transaction; the log line is the evidence.
            log.error("Audit of a staff loan payout request failed", ex);
        }
    }

    private static NotFoundException notFound(Long id) {
        return new NotFoundException("Staff loan " + id + " not found");
    }
}
