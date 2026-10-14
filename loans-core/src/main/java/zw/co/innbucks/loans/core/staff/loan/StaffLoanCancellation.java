package zw.co.innbucks.loans.core.staff.loan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreCalls;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupons;
import zw.co.innbucks.loans.core.getmore.GetMoreException;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncTrigger;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherSync;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherCodeVault;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionRepository;
import zw.co.innbucks.loans.core.voucher.VoucherRefundRepository;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.core.voucher.VoucherStopReason;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssue;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueKind;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Credit's cancel of a Staff Grocery Loan (POST /staff-loans/{id}/cancel), now that a loan can be paid out as its
 * GetMore voucher at acceptance (D6). Not transactional: GetMore is called with no transaction open, between short
 * transactions of its own.
 *
 * <ul>
 *   <li><b>Awaiting its voucher</b>, with no GetMore issuance (or an abandoned one): cancelled, as before.</li>
 *   <li><b>Awaiting it, with an attempt calling GetMore right now</b>: refused (409), to be repeated a minute later;
 *       stopping the loan under a call that may be minting its coupon would leave that coupon live.</li>
 *   <li><b>Awaiting it otherwise</b>: cancelled, its issuance sent to CANCELLING, and whatever the issuance left at
 *       GetMore withdrawn there and then (best effort; the message says whether it is done).</li>
 *   <li><b>Paid out as a GetMore voucher of this host's mode</b>: cancelled only if nothing was ever spent from it,
 *       as GetMore's own records say, not only loans': the voucher is synced (refused while GetMore cannot be read, or
 *       an open blocking sync issue stands, or anything was ever spent, refunded or held at the till); its coupon is
 *       withdrawn at GetMore (nothing changes in loans if that fails); synced again, to catch a sale that reached the
 *       till just before the withdrawal; and only then, with the loan and the voucher locked, both are cancelled. A
 *       second sync that cannot complete stops the voucher (its code is dead at GetMore anyway) and leaves the loan to
 *       a repeat of the request, which is safe. A spend that raced in, a write-off approved or pending meanwhile, or
 *       the loan no longer DISBURSED: the voucher is stopped and the loan stands.</li>
 *   <li><b>Anything else</b>: refused, as before.</li>
 * </ul>
 *
 * <p>The voucher's sync lock is held across the whole of the paid-out case, so no other sync of it interleaves. The
 * success answer is the loan; {@link Result#message()} says what became of the coupon.</p>
 */
@Slf4j
@Service
public class StaffLoanCancellation {

    static final String CANCELLED = "Cancelled";
    static final String CANCELLED_WITHDRAWN = "Cancelled. The voucher's coupon is withdrawn at GetMore.";
    static final String CANCELLED_WITHDRAWAL_PENDING = "Cancelled. Withdrawing the voucher's coupon at GetMore is still"
            + " pending; it is retried on the next voucher-issuance call or payday sync.";

    /** The loan as it now stands, and what became of its GetMore coupon, in words. */
    public record Result(StaffLoanResponse loan, String message) {
    }

    private final StaffLoanRepository loanRepository;
    private final StaffLoanVoucherIssuanceRepository issuanceRepository;
    private final StaffLoanWriteOffRepository writeOffRepository;
    private final StaffLoanService loanService;
    private final StaffLoanVoucherIssuer issuer;
    private final StaffLoanPolicy policy;
    private final GetMoreVoucherSync sync;
    private final GetMoreCoupons coupons;
    private final GetMoreProperties properties;
    private final VoucherService voucherService;
    private final VoucherRepository voucherRepository;
    private final VoucherRedemptionRepository redemptionRepository;
    private final VoucherRefundRepository refundRepository;
    private final VoucherSyncIssueRepository issueRepository;
    private final VoucherCodeVault vault;
    private final AuthService authService;
    private final MarketTimeZone marketTimeZone;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public StaffLoanCancellation(StaffLoanRepository loanRepository,
                                 StaffLoanVoucherIssuanceRepository issuanceRepository,
                                 StaffLoanWriteOffRepository writeOffRepository, StaffLoanService loanService,
                                 StaffLoanVoucherIssuer issuer, StaffLoanPolicy policy, GetMoreVoucherSync sync,
                                 GetMoreCoupons coupons, GetMoreProperties properties, VoucherService voucherService,
                                 VoucherRepository voucherRepository, VoucherRedemptionRepository redemptionRepository,
                                 VoucherRefundRepository refundRepository, VoucherSyncIssueRepository issueRepository,
                                 VoucherCodeVault vault, AuthService authService, MarketTimeZone marketTimeZone,
                                 PlatformTransactionManager transactionManager) {
        this.loanRepository = loanRepository;
        this.issuanceRepository = issuanceRepository;
        this.writeOffRepository = writeOffRepository;
        this.loanService = loanService;
        this.issuer = issuer;
        this.policy = policy;
        this.sync = sync;
        this.coupons = coupons;
        this.properties = properties;
        this.voucherService = voucherService;
        this.voucherRepository = voucherRepository;
        this.redemptionRepository = redemptionRepository;
        this.refundRepository = refundRepository;
        this.issueRepository = issueRepository;
        this.vault = vault;
        this.authService = authService;
        this.marketTimeZone = marketTimeZone;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * Cancels loan {@code staffLoanId} (see the class), with who and why.
     *
     * @throws NotFoundException           no such loan
     * @throws IssuanceInProgressException its GetMore voucher is being issued right now
     * @throws ConflictException           it cannot be cancelled: closed, its voucher used, a sync issue open, a
     *                                     write-off; or a spend raced in (its voucher is then stopped)
     * @throws GetMoreUnavailableException GetMore could not be read or asked; repeat the request
     * @throws GetMoreRejectedException    GetMore refused to withdraw the coupon; nothing changed in loans
     * @throws GetMoreOutcomeUnknownException the withdrawal's answer was lost; repeat the request, it is safe
     */
    public Result cancel(Long staffLoanId, String reason) {
        GetMoreCalls.requireNoTransaction();
        String by = authService.getLoggedInUsername();
        String why = reason.strip();
        First first = write(status -> first(staffLoanId, by, why));
        return switch (first.kind()) {
            case CANCELLED -> new Result(first.loan(), CANCELLED);
            case CANCELLING -> {
                StaffLoanVoucherIssuer.Cleanup cleanup = issuer.cleanup(first.issuanceId());
                yield new Result(first.loan(), cleanup.withdrawn() ? CANCELLED_WITHDRAWN
                        : CANCELLED_WITHDRAWAL_PENDING);
            }
            case GETMORE -> sync.withVoucherLock(first.voucherId(),
                    () -> cancelPaidOut(staffLoanId, first.voucherId(), by, why));
        };
    }

    /** [T1] The loan locked, then its issuance: what kind of cancel this is, the first two done here. */
    private First first(Long staffLoanId, String by, String reason) {
        StaffLoan loan = loanRepository.lockById(staffLoanId).orElseThrow(() -> new NotFoundException(
                "Staff loan " + staffLoanId + " not found"));
        if (loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT) {
            Long cancelling = loanService.cancelAwaiting(loan, by, reason, "admin-portal", true).orElse(null);
            return new First(cancelling == null ? Kind.CANCELLED : Kind.CANCELLING, response(loan), cancelling, null);
        }
        if (loan.getStatus() == StaffLoanStatus.DISBURSED) {
            Voucher voucher = voucherRepository.findByStaffLoanId(loan.getId()).orElse(null);
            if (voucher != null && voucher.isGetMore()) {
                if (voucher.getStoreMode() != properties.getMode()) {
                    throw new ConflictException("Staff loan " + loan.getReference() + " was paid out as a coupon of"
                            + " the " + voucher.getStoreMode() + " GetMore store and GetMore is "
                            + properties.getMode() + " on this host: it cannot be cancelled here");
                }
                return new First(Kind.GETMORE, null, null, voucher.getId());
            }
        }
        throw new ConflictException("Staff loan " + loan.getReference() + " is " + loan.getStatus()
                + "; only a loan awaiting disbursement can be cancelled");
    }

    /** The paid-out case, holding the voucher's sync lock. */
    private Result cancelPaidOut(Long staffLoanId, Long voucherId, String by, String reason) {
        GetMoreSyncResult before = sync.sync(voucherId, GetMoreSyncTrigger.CANCELLATION);
        Touch touch = read(status -> touch(voucherId));
        requireUnused(voucherId, before, touch);

        GetMoreCouponDeletion deletion = coupons.withdraw(List.of(touch.code()));
        if (deletion.failedItems().contains(touch.code())) {
            throw new GetMoreRejectedException(GetMoreException.Operation.DELETE_COUPONS,
                    GetMoreRejectedException.Reason.INVALID, "GetMore did not withdraw the coupon of voucher "
                    + voucherId + " (" + GetMoreCodes.maskedCode(touch.code()) + "); nothing was changed in loans");
        }
        writeTransaction.executeWithoutResult(status -> voucherService.markCouponWithdrawn(voucherId, now()));
        log.info("Cancelling staff loan {}: the coupon of voucher {} ({}) is withdrawn at GetMore", touch.loanAccount(),
                voucherId, GetMoreCodes.maskedCode(touch.code()));

        GetMoreSyncResult after = sync.sync(voucherId, GetMoreSyncTrigger.CANCELLATION);
        if (!complete(after)) {
            writeTransaction.executeWithoutResult(status -> {
                LocalDateTime now = now();
                voucherService.stopGetMoreNow(voucherId, VoucherStopReason.BANK_STOPPED, now);
                voucherService.markCouponWithdrawn(voucherId, now);
            });
            log.warn("Cancelling staff loan {}: voucher {} is withdrawn and stopped, but GetMore could not be read"
                    + " again ({}); the loan stands until the cancel is repeated", touch.loanAccount(), voucherId,
                    after.outcome());
            throw new GetMoreUnavailableException(null, unavailableReason(after.outcome()),
                    "The coupon is withdrawn at GetMore; repeat the request to finish cancelling: it is safe.");
        }

        Decision decision = write(status -> decide(staffLoanId, voucherId, after, by, reason));
        return switch (decision.kind()) {
            case CANCELLED -> new Result(decision.loan(), CANCELLED_WITHDRAWN);
            case STANDS -> throw new ConflictException("Staff loan " + decision.reference() + " "
                    + decision.standing() + "; its coupon is withdrawn and the loan stands.");
            case RACED -> throw new ConflictException("A spend of " + decision.spent() + " reached the voucher while"
                    + " cancelling; it has been stopped at GetMore and the loan stands.");
        };
    }

    /**
     * Refuses a voucher GetMore could not be read for, one with an open blocking sync issue, one ever used (spent,
     * refunded, held at the till, an order standing at GetMore), and one that lapsed or was stopped other than by an
     * earlier cancellation.
     */
    private void requireUnused(Long voucherId, GetMoreSyncResult synced, Touch touch) {
        if (!synced.outcome().read()) {
            throw new GetMoreUnavailableException(null, unavailableReason(synced.outcome()), "Cannot read voucher "
                    + voucherId + " at GetMore right now; try again.");
        }
        if (touch.blockedBy() != null) {
            throw new ConflictException("Voucher " + voucherId + " has an open GetMore sync issue ("
                    + touch.blockedBy() + "); resolve it before cancelling.");
        }
        if (touch.used() || synced.liveOrdersOnCoupon() > 0) {
            throw new ConflictException("The voucher of " + touch.loanAccount() + " has been used ("
                    + money(touch.currency(), touch.spent()) + " spent, " + money(touch.currency(), touch.held())
                    + " held at the till); a loan whose voucher was used cannot be cancelled.");
        }
        if (touch.status() != VoucherStatus.ISSUED && !(touch.status() == VoucherStatus.EXPIRED
                && touch.stopped() == VoucherStopReason.BANK_STOPPED)) {
            throw new ConflictException("Voucher " + voucherId + " of " + touch.loanAccount() + " is "
                    + touch.status() + (touch.stopped() == null ? "" : " (" + touch.stopped() + ")")
                    + "; only a loan whose voucher can still be spent can be cancelled.");
        }
    }

    /** [T2] The loan, then the voucher, locked: cancel both, or stop the voucher and let the loan stand. */
    private Decision decide(Long staffLoanId, Long voucherId, GetMoreSyncResult after, String by, String reason) {
        LocalDateTime now = now();
        StaffLoan loan = loanRepository.lockById(staffLoanId).orElseThrow(() -> new NotFoundException(
                "Staff loan " + staffLoanId + " not found"));
        voucherRepository.lockById(voucherId).orElseThrow();
        Touch touch = touch(voucherId);
        boolean writeOffPending = writeOffRepository.findByStaffLoanIdAndStatus(staffLoanId,
                StaffLoanWriteOffStatus.PENDING).isPresent();
        if (loan.getStatus() != StaffLoanStatus.DISBURSED || writeOffPending) {
            voucherService.stopGetMoreNow(voucherId, VoucherStopReason.BANK_STOPPED, now);
            voucherService.markCouponWithdrawn(voucherId, now);
            log.warn("Cancelling staff loan {}: it is {}{}; its voucher {} is stopped and the loan stands",
                    loan.getReference(), loan.getStatus(), writeOffPending ? " with a write-off pending" : "",
                    voucherId);
            return Decision.stands(loan.getReference(), loan.getStatus() != StaffLoanStatus.DISBURSED
                    ? "is " + loan.getStatus() : "has a write-off pending");
        }
        if (touch.used() || touch.blockedBy() != null || after.liveOrdersOnCoupon() > 0) {
            voucherService.stopGetMoreNow(voucherId, VoucherStopReason.BANK_STOPPED, now);
            voucherService.markCouponWithdrawn(voucherId, now);
            String spent = money(touch.currency(), touch.spent().add(touch.held()));
            log.warn("Cancelling staff loan {}: a spend of {} reached voucher {} meanwhile; it is stopped and the loan"
                    + " stands", loan.getReference(), spent, voucherId);
            return Decision.raced(spent);
        }
        voucherService.cancelGetMoreForLoan(voucherId, by, reason, now);
        voucherService.markCouponWithdrawn(voucherId, now);
        loanService.cancelLocked(loan, by, reason, "admin-portal");
        log.info("Staff loan {} cancelled by {} with its GetMore voucher {}, never spent", loan.getReference(), by,
                voucherId);
        return Decision.cancelled(response(loan));
    }

    /** The voucher as the decision needs it, read now. The code is in memory only, and never in toString. */
    private Touch touch(Long voucherId) {
        Voucher voucher = voucherRepository.findById(voucherId).orElseThrow();
        VoucherSyncIssueKind blocked = voucher.getSyncBlockedBy();
        if (blocked == null) {
            blocked = issueRepository.findByVoucherIdAndClearedAtIsNullOrderByIdAsc(voucherId).stream()
                    .filter(VoucherSyncIssue::isBlocking).map(VoucherSyncIssue::getKind).findFirst().orElse(null);
        }
        boolean rows = redemptionRepository.existsByVoucherId(voucherId)
                || refundRepository.existsByVoucherId(voucherId);
        return new Touch(vault.decrypt(voucher.getCodeCiphertext()), voucher.getLoanAccount(), voucher.getCurrency(),
                voucher.getRedeemedAmount(), voucher.getHeldAmount(), voucher.getRefundedAmount(), rows, blocked,
                voucher.statusAt(now()), voucher.getStoppedReason());
    }

    private StaffLoanResponse response(StaffLoan loan) {
        return StaffLoanResponse.of(loan, marketTimeZone.today(), policy.arrearsGraceDays());
    }

    private static boolean complete(GetMoreSyncResult result) {
        return result.outcome() == GetMoreSyncResult.Outcome.SYNCED
                || result.outcome() == GetMoreSyncResult.Outcome.UNCHANGED;
    }

    /** Busy or racing syncs are worth repeating at once; anything else is GetMore that could not be read. */
    private static GetMoreUnavailableException.Reason unavailableReason(GetMoreSyncResult.Outcome outcome) {
        return switch (outcome) {
            case BUSY, STALE_SNAPSHOT, CONFLICT_RETRY -> GetMoreUnavailableException.Reason.BUSY;
            default -> GetMoreUnavailableException.Reason.READ_FAILED;
        };
    }

    private static String money(String currency, BigDecimal amount) {
        return currency + " " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private LocalDateTime now() {
        return marketTimeZone.nowUtc().truncatedTo(ChronoUnit.MICROS);
    }

    private <T> T write(TransactionCallback<T> work) {
        return writeTransaction.execute(work);
    }

    private <T> T read(TransactionCallback<T> work) {
        return readTransaction.execute(work);
    }

    private enum Kind { CANCELLED, CANCELLING, GETMORE }

    private record First(Kind kind, StaffLoanResponse loan, Long issuanceId, Long voucherId) {
    }

    /** What the voucher shows. Holds its code in memory only. */
    private record Touch(String code, String loanAccount, String currency, BigDecimal spent, BigDecimal held,
                         BigDecimal refunded, boolean rows, VoucherSyncIssueKind blockedBy, VoucherStatus status,
                         VoucherStopReason stopped) {

        /** Anything ever spent, refunded or held, or any row recording it. */
        boolean used() {
            return rows || spent.signum() != 0 || held.signum() != 0 || refunded.signum() != 0;
        }

        @Override
        public String toString() {
            return "Touch[loanAccount=" + loanAccount + ", code=" + GetMoreCodes.maskedCode(code) + ", status="
                    + status + "]";
        }
    }

    private enum DecisionKind { CANCELLED, STANDS, RACED }

    private record Decision(DecisionKind kind, StaffLoanResponse loan, String reference, String standing,
                            String spent) {

        static Decision cancelled(StaffLoanResponse loan) {
            return new Decision(DecisionKind.CANCELLED, loan, null, null, null);
        }

        static Decision stands(String reference, String standing) {
            return new Decision(DecisionKind.STANDS, null, reference, standing, null);
        }

        static Decision raced(String spent) {
            return new Decision(DecisionKind.RACED, null, null, null, spent);
        }
    }
}
