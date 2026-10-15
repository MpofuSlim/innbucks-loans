package zw.co.innbucks.loans.core.voucher;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.CouponActivity;
import zw.co.innbucks.loans.core.getmore.Deadline;
import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreCalls;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupons;
import zw.co.innbucks.loans.core.getmore.GetMoreException;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreNotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreStore;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.voucher.GetMoreSpendPlan.Issue;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult.Outcome;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Reads a GetMore voucher's spends and refunds back from GetMore and records them: the ONE way a GetMore voucher's
 * spends reach loans (its till refuses the codes), the same in every mode. Never called by a job: a borrower's read, a
 * simulator action, staff, the cancellation and the payday sync start it.
 *
 * <ol>
 *   <li><b>Read</b>, in a short read-only transaction: the voucher, its code (decrypted in memory only) and the orders
 *       loans has recorded refunds against. A loans voucher is SKIPPED; one of another store mode is
 * MODE_MISMATCH.</li>   <li><b>Ask GetMore</b>, with no transaction open: the prefix's coupons, orders and credit memos
 *       ({@link GetMoreCoupons#activity}). A failed read writes nothing (UNAVAILABLE). Then, best effort: other
 *       unused coupons on the prefix are withdrawn; a coupon missing from a complete read is looked up, and "No such
 *       entity" means GetMore deleted it (the voucher is stopped, STORE_WITHDRAWN); a lapsed voucher's code is
 *       withdrawn, the only real stop at Magento; an open voucher whose coupon's expiry date was never confirmed has
 *       it set.</li>
 *   <li><b>Apply</b>, in one transaction under the voucher's row lock ({@link GetMoreVoucherLedger}); then the audit
 *       rows, once it has committed.</li>
 * </ol>
 *
 * <p>Two syncs of one voucher never overlap: each holds an in-process lock for the voucher (one of 64 stripes) from the
 * read to the apply, waiting at most {@code loans.getmore.sync.lock-wait} (BUSY otherwise). Loans runs one replica
 * ({@code Recreate}), so a process lock is enough; a second replica would need a database lock instead. Without it, two
 * syncs could read GetMore in either order and the older read be applied last, which would look like an invoice going
 * down. The lock is reentrant, so a cancellation holds it across its own two syncs ({@link #withVoucherLock}).</p>
 */
@Slf4j
@Component
public class GetMoreVoucherSync {

    static final int STRIPES = 64;
    static final String METRIC = "loans.getmore.sync";
    static final String ISSUES_METRIC = "loans.getmore.sync.issues";

    private final VoucherRepository voucherRepository;
    private final VoucherRefundRepository refundRepository;
    private final VoucherCodeVault vault;
    private final GetMoreCoupons coupons;
    private final GetMoreStore store;
    private final GetMoreAmountBinding binding;
    private final GetMoreProperties properties;
    private final GetMoreVoucherLedger ledger;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate readTransaction;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public GetMoreVoucherSync(VoucherRepository voucherRepository, VoucherRefundRepository refundRepository,
                              VoucherCodeVault vault, GetMoreCoupons coupons, GetMoreStore store,
                              GetMoreAmountBinding binding, GetMoreProperties properties, GetMoreVoucherLedger ledger,
                              AuditService auditService, MarketTimeZone marketTimeZone,
                              PlatformTransactionManager transactionManager,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this.voucherRepository = voucherRepository;
        this.refundRepository = refundRepository;
        this.vault = vault;
        this.coupons = coupons;
        this.store = store;
        this.binding = binding;
        this.properties = properties;
        this.ledger = ledger;
        this.auditService = auditService;
        this.marketTimeZone = marketTimeZone;
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
        if (this.meterRegistry != null) {
            for (Outcome outcome : Outcome.values()) {
                Counter.builder(METRIC).tag("outcome", outcome.name())
                        .description("GetMore syncs of a voucher, by what they did").register(this.meterRegistry);
            }
        }
    }

    /**
     * Syncs one voucher. Called with no transaction open. Never throws for anything GetMore did; the outcome says
     * what happened.
     *
     * @throws zw.co.innbucks.loans.core.exception.NotFoundException no such voucher
     * @throws VouchersUnavailableException                          the voucher code keys are not configured
     */
    public GetMoreSyncResult sync(Long voucherId, GetMoreSyncTrigger trigger) {
        GetMoreCalls.requireNoTransaction();
        GetMoreSyncResult result;
        try {
            result = withVoucherLock(voucherId, () -> syncLocked(voucherId, trigger));
        } catch (GetMoreUnavailableException busy) {
            if (busy.reason() != GetMoreUnavailableException.Reason.BUSY) {
                throw busy;
            }
            log.warn("GetMore sync of voucher {} ({}) not run: another is still running after {}", voucherId, trigger,
                    properties.getSync().getLockWait());
            result = GetMoreSyncResult.nothing(Outcome.BUSY, null);
        }
        count(result.outcome());
        return result;
    }

    /**
     * Runs {@code work} holding this voucher's sync lock, so no sync of it runs meanwhile. Reentrant: a sync inside
     * {@code work} runs under the same hold.
     *
     * @throws GetMoreUnavailableException {@code BUSY} when the lock was not free within the configured wait
     */
    public <T> T withVoucherLock(Long voucherId, Supplier<T> work) {
        ReentrantLock lock = locks[Math.floorMod(Long.hashCode(voucherId), STRIPES)];
        Duration wait = properties.getSync().getLockWait();
        boolean held;
        try {
            held = lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            held = false;
        }
        if (!held) {
            throw new GetMoreUnavailableException(null, GetMoreUnavailableException.Reason.BUSY,
                    "another sync of voucher " + voucherId + " is running");
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    private GetMoreSyncResult syncLocked(Long voucherId, GetMoreSyncTrigger trigger) {
        GetMoreMode mode = properties.getMode();
        Read read = readTransaction.execute(status -> read(voucherId, mode));
        if (read.early() != null) {
            log.info("GetMore sync of voucher {} ({}): {}", voucherId, trigger, read.early().outcome());
            return read.early();
        }
        String masked = GetMoreCodes.maskedCode(read.code());
        CouponActivity activity;
        try {
            activity = coupons.activity(read.prefix(), read.ordersWithRefunds(), Deadline.none());
        } catch (GetMoreException unreadable) {
            log.warn("GetMore sync of voucher {} ({}, {}): GetMore could not be read, nothing written: {}", voucherId,
                    masked, trigger, GetMoreCodes.maskAll(unreadable.getMessage(), List.of(read.code())));
            return GetMoreSyncResult.nothing(Outcome.UNAVAILABLE, read.voucher());
        }

        List<Issue> syncIssues = new ArrayList<>(withdrawExtras(read, activity));
        GetMoreCoupon own = activity.couponsOnPrefix().stream()
                .filter(coupon -> coupon.couponId() == read.couponId()).findFirst().orElse(null);
        boolean gone = own == null && activity.complete() && !read.couponWithdrawn() && gone(read);
        boolean withdrawnNow = !gone && read.lapsed() && !read.cancelled() && !read.couponWithdrawn()
                && withdrawLapsed(read);
        boolean expirySet = own != null && read.open() && !read.lapsed() && !read.couponExpirySet()
                && setExpiry(read, own);

        GetMoreVoucherLedger.Applied applied;
        try {
            applied = ledger.apply(new GetMoreVoucherLedger.Snapshot(voucherId, read.code(), read.snapshotStartedAt(),
                    activity, syncIssues, gone, withdrawnNow, expirySet, own == null ? null : own.timesUsed(),
                    binding.partialSpending(), trigger), mode);
        } catch (DataIntegrityViolationException conflict) {
            log.warn("GetMore sync of voucher {} ({}, {}): a row it was adding was added at the same moment; nothing"
                    + " written, the next sync plans again", voucherId, masked, trigger);
            return GetMoreSyncResult.nothing(Outcome.CONFLICT_RETRY, read.voucher());
        }
        for (AuditLog.AuditLogBuilder audit : applied.audits()) {
            auditService.record(audit);
        }
        if (meterRegistry != null) {
            applied.opened().forEach(kind -> meterRegistry.counter(ISSUES_METRIC, "kind", kind.name()).increment());
        }
        GetMoreSyncResult result = applied.result();
        if (result.outcome() == Outcome.BLOCKED) {
            log.warn("GetMore sync of voucher {} ({}, {}): BLOCKED at {}; {} spend(s), {} refund(s) recorded before"
                            + " it, held {}", voucherId, masked, trigger, result.voucher().syncBlockedBy(),
                    result.spendsRecorded(), result.refundsRecorded(), result.heldAmount());
        } else {
            log.info("GetMore sync of voucher {} ({}, {}): {}; {} spend(s), {} refund(s) recorded, held {}, {} open"
                            + " issue(s)", voucherId, masked, trigger, result.outcome(), result.spendsRecorded(),
                    result.refundsRecorded(), result.heldAmount(), result.issues().size());
        }
        return result;
    }

    /** The voucher as a sync needs it, or the outcome when there is nothing to ask GetMore. */
    private Read read(Long voucherId, GetMoreMode mode) {
        Voucher voucher = voucherRepository.findById(voucherId).orElseThrow(() -> VoucherService.notFound(voucherId));
        LocalDateTime now = marketTimeZone.nowUtc();
        VoucherResponse view = VoucherResponse.of(voucher, now);
        if (!voucher.isGetMore()) {
            return Read.early(GetMoreSyncResult.nothing(Outcome.SKIPPED, view));
        }
        if (voucher.getStoreMode() != mode) {
            return Read.early(GetMoreSyncResult.nothing(Outcome.MODE_MISMATCH, view));
        }
        vault.requireConfigured();
        Set<Long> ordersWithRefunds = new HashSet<>();
        refundRepository.findByVoucherIdOrderByIdAsc(voucherId)
                .forEach(refund -> ordersWithRefunds.add(refund.getExternalOrderId()));
        return new Read(null, view, vault.decrypt(voucher.getCodeCiphertext()), voucher.getCodePrefix(),
                voucher.getExternalCouponId(), voucher.lapsed(now), voucher.getStatus() == VoucherStatus.CANCELLED,
                voucher.getStatus().isOpen(), voucher.getExpiresAt(), voucher.getCouponExpirySetAt() != null,
                voucher.getCouponWithdrawnAt() != null, ordersWithRefunds, now.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Withdraws every other unused coupon on the prefix (a lost generate's twin, an adopted duplicate): best effort.
     *
     * @return an EXTRA_COUPON warning for each other coupon still there: used, or not confirmed withdrawn
     */
    private List<Issue> withdrawExtras(Read read, CouponActivity activity) {
        List<GetMoreCoupon> extras = activity.couponsOnPrefix().stream()
                .filter(coupon -> coupon.couponId() != read.couponId()).toList();
        if (extras.isEmpty()) {
            return List.of();
        }
        List<String> unused = extras.stream().filter(coupon -> coupon.timesUsed() == 0).map(GetMoreCoupon::code)
                .toList();
        Set<String> failed = new HashSet<>();
        if (!unused.isEmpty()) {
            try {
                failed.addAll(coupons.withdraw(unused).failedItems());
            } catch (RuntimeException withdrawFailed) {
                failed.addAll(unused);
                log.warn("GetMore: could not withdraw {} extra coupon(s) on the prefix of voucher {}: {}",
                        unused.size(), read.voucher().id(), GetMoreCodes.maskAll(String.valueOf(
                                withdrawFailed.getMessage()), unused));
            }
        }
        List<Issue> remaining = new ArrayList<>();
        for (GetMoreCoupon extra : extras) {
            if (extra.timesUsed() > 0 || failed.contains(extra.code())) {
                remaining.add(new Issue(VoucherSyncIssueKind.EXTRA_COUPON, "coupon:" + extra.couponId(), "coupon "
                        + extra.couponId() + " (" + GetMoreCodes.maskedCode(extra.code()) + ") on the voucher's"
                        + " prefix is not its own and is still there: "
                        + (extra.timesUsed() > 0 ? "used " + extra.timesUsed() + " time(s)"
                        : "withdrawing it failed")));
            } else {
                log.info("GetMore: extra coupon {} ({}) on the prefix of voucher {} withdrawn", extra.couponId(),
                        GetMoreCodes.maskedCode(extra.code()), read.voucher().id());
            }
        }
        return remaining;
    }

    /** Whether GetMore says the voucher's coupon no longer exists. Any other answer is not proof of that. */
    private boolean gone(Read read) {
        try {
            store.coupon(read.couponId());
            return false;
        } catch (GetMoreNotFoundException notFound) {
            log.warn("GetMore: coupon {} of voucher {} ({}) is gone at GetMore; the voucher is stopped",
                    read.couponId(), read.voucher().id(), GetMoreCodes.maskedCode(read.code()));
            return true;
        } catch (RuntimeException unknown) {
            log.warn("GetMore: coupon {} of voucher {} is not on its prefix and could not be looked up: {}",
                    read.couponId(), read.voucher().id(), GetMoreCodes.maskAll(String.valueOf(unknown.getMessage()),
                            List.of(read.code())));
            return false;
        }
    }

    /**
     * Tells GetMore when an open voucher's coupon lapses, when that was never confirmed: the PUT after issue is best
     * effort and is not even sent once an acceptance has run out of its time, so the sync is what repeats it. Magento
     * stores the date and ignores it; the simulator enforces it (A4). Best effort: a failure is tried again by the next
     * sync.
     *
     * @return whether GetMore confirmed it
     */
    private boolean setExpiry(Read read, GetMoreCoupon own) {
        try {
            coupons.setExpiry(own, read.expiresAt());
            return true;
        } catch (RuntimeException notSet) {
            log.warn("GetMore: the expiry date of voucher {}'s coupon ({}) was not set; the next sync tries again: {}",
                    read.voucher().id(), GetMoreCodes.maskedCode(read.code()), GetMoreCodes.maskAll(
                            String.valueOf(notSet.getMessage()), List.of(read.code())));
            return false;
        }
    }

    /** Withdraws a lapsed voucher's code: Magento ignores a coupon's expiry date, so deleting it is the only stop. */
    private boolean withdrawLapsed(Read read) {
        try {
            GetMoreCouponDeletion deletion = coupons.withdraw(List.of(read.code()));
            if (deletion.failedItems().contains(read.code())) {
                log.warn("GetMore: the lapsed code of voucher {} ({}) was not withdrawn; the next sync tries again",
                        read.voucher().id(), GetMoreCodes.maskedCode(read.code()));
                return false;
            }
            return true;
        } catch (RuntimeException withdrawFailed) {
            log.warn("GetMore: the lapsed code of voucher {} ({}) could not be withdrawn; the next sync retries: {}",
                    read.voucher().id(), GetMoreCodes.maskedCode(read.code()), GetMoreCodes.maskAll(
                            String.valueOf(withdrawFailed.getMessage()), List.of(read.code())));
            return false;
        }
    }

    private void count(Outcome outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC, "outcome", outcome.name()).increment();
        }
    }

    /** What the read found. The code is in memory only, and never in toString. */
    private record Read(GetMoreSyncResult early, VoucherResponse voucher, String code, String prefix, long couponId,
                        boolean lapsed, boolean cancelled, boolean open, LocalDateTime expiresAt,
                        boolean couponExpirySet, boolean couponWithdrawn, Set<Long> ordersWithRefunds,
                        LocalDateTime snapshotStartedAt) {

        static Read early(GetMoreSyncResult result) {
            return new Read(result, result.voucher(), null, null, 0, false, false, false, null, false, false,
                    Set.of(), null);
        }

        @Override
        public String toString() {
            return "Read[voucherId=" + (voucher == null ? null : voucher.id()) + ", code="
                    + GetMoreCodes.maskedCode(code) + ", early=" + early + "]";
        }
    }
}
