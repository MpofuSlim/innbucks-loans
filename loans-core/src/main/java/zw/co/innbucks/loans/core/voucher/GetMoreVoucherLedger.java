package zw.co.innbucks.loans.core.voucher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.CouponActivity;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.voucher.GetMoreSpendPlan.Issue;
import zw.co.innbucks.loans.core.voucher.GetMoreSpendPlan.Plan;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult.Outcome;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The write half of a GetMore sync: in one transaction, under the voucher's row lock, it plans what a GetMore snapshot
 * adds ({@link GetMoreSpendPlan}) against the rows recorded by then, adds those rows, moves the voucher, and records
 * the issues seen. A snapshot started no later than the one last applied is discarded (a backstop: the sync's own lock
 * already keeps two from overlapping). A row a concurrent writer added first fails the insert and rolls the whole of it
 * back; the next sync plans again.
 *
 * <p>It audits nothing itself: the audit rows go in their own transactions, so they are handed back and written only
 * once this one has committed.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetMoreVoucherLedger {

    private static final String CHANNEL = "getmore-sync";

    private final VoucherRepository voucherRepository;
    private final VoucherRedemptionRepository redemptionRepository;
    private final VoucherRefundRepository refundRepository;
    private final VoucherSyncIssueRepository issueRepository;
    private final MarketTimeZone marketTimeZone;

    /**
     * One snapshot of GetMore, read with no transaction open, to apply.
     *
     * @param code              the voucher's code, decrypted in memory only
     * @param snapshotStartedAt when the reads began: a sync applied since makes this one stale
     * @param syncIssues        issues the sync itself saw (extra coupons on the prefix)
     * @param couponGone        GetMore answered "No such entity" for the voucher's coupon
     * @param withdrawnNow      the sync withdrew the lapsed code at GetMore
     * @param expirySet         GetMore confirmed the coupon's expiry date, set by the sync
     * @param ownTimesUsed      Magento's use count of the voucher's own coupon; null when it was not on the prefix
     * @param partialSpending   whether a coupon can be spent over several purchases in this store
     */
    public record Snapshot(Long voucherId, String code, LocalDateTime snapshotStartedAt, CouponActivity activity,
                           List<Issue> syncIssues, boolean couponGone, boolean withdrawnNow, boolean expirySet,
                           Integer ownTimesUsed, boolean partialSpending, GetMoreSyncTrigger trigger) {

        public Snapshot {
            syncIssues = syncIssues == null ? List.of() : List.copyOf(syncIssues);
        }

        @Override
        public String toString() {
            return "Snapshot[voucherId=" + voucherId + ", code=" + GetMoreCodes.maskedCode(code)
                    + ", snapshotStartedAt=" + snapshotStartedAt + ", trigger=" + trigger + "]";
        }
    }

    /**
     * @param audits to record once the transaction has committed
     * @param opened the issue kinds this sync opened (new, or seen again after being cleared)
     */
    public record Applied(GetMoreSyncResult result, List<AuditLog.AuditLogBuilder> audits,
                          List<VoucherSyncIssueKind> opened) {
    }

    @Transactional
    public Applied apply(Snapshot snapshot, GetMoreMode mode) {
        Voucher voucher = voucherRepository.lockById(snapshot.voucherId())
                .orElseThrow(() -> VoucherService.notFound(snapshot.voucherId()));
        LocalDateTime now = marketTimeZone.nowUtc();
        if (!voucher.isGetMore() || voucher.getStoreMode() != mode) {
            return nothing(voucher.isGetMore() ? Outcome.MODE_MISMATCH : Outcome.SKIPPED, voucher, now);
        }
        if (voucher.getSyncedAt() != null && !snapshot.snapshotStartedAt().isAfter(voucher.getSyncedAt())) {
            log.info("GetMore sync of voucher {} discarded: its snapshot ({}) is no newer than the one applied ({})",
                    voucher.getId(), snapshot.snapshotStartedAt(), voucher.getSyncedAt());
            return nothing(Outcome.STALE_SNAPSHOT, voucher, now);
        }
        BigDecimal heldBefore = voucher.getHeldAmount();
        VoucherStatus statusBefore = voucher.getStatus();
        Plan plan = GetMoreSpendPlan.plan(GetMoreSpendPlan.Snapshot.of(voucher, snapshot.code()),
                redemptionRepository.findByVoucherIdOrderByIdAsc(voucher.getId()),
                refundRepository.findByVoucherIdOrderByIdAsc(voucher.getId()), snapshot.activity(), now);
        for (VoucherRedemption spend : plan.spends()) {
            redemptionRepository.saveAndFlush(spend);
        }
        for (VoucherRefund refund : plan.refunds()) {
            refundRepository.saveAndFlush(refund);
        }
        voucher.applyGetMoreSync(plan.net(), plan.refunded(), plan.held(), plan.lastSpentAt(), plan.lastOutlet(),
                snapshot.snapshotStartedAt(), plan.blockedBy(), now);

        List<Issue> seen = new ArrayList<>(plan.issues());
        seen.addAll(snapshot.syncIssues());
        boolean withdrawnBefore = voucher.getCouponWithdrawnAt() != null;
        if (snapshot.couponGone() || snapshot.withdrawnNow()) {
            voucher.couponWithdrawn(now);
        }
        if (snapshot.expirySet()) {
            voucher.couponExpirySet(now);
        }
        VoucherStopReason stopped = null;
        if (snapshot.couponGone() && voucher.stopNow(VoucherStopReason.STORE_WITHDRAWN, now)) {
            stopped = VoucherStopReason.STORE_WITHDRAWN;
        } else if (!snapshot.partialSpending() && snapshot.ownTimesUsed() != null && snapshot.ownTimesUsed() >= 1
                && plan.net().add(plan.held()).compareTo(voucher.getFaceValue()) < 0
                && voucher.stopNow(VoucherStopReason.SINGLE_USE_EXHAUSTED, now)) {
            stopped = VoucherStopReason.SINGLE_USE_EXHAUSTED;
        }
        if (snapshot.couponGone() || voucher.getStoppedReason() == VoucherStopReason.STORE_WITHDRAWN) {
            seen.add(new Issue(VoucherSyncIssueKind.COUPON_GONE, "coupon:" + voucher.getExternalCouponId(),
                    "coupon " + voucher.getExternalCouponId() + " (" + voucher.maskedCode() + ") is gone at GetMore:"
                            + " the voucher is stopped"));
        }
        if (voucher.getStoppedReason() == VoucherStopReason.SINGLE_USE_EXHAUSTED) {
            seen.add(new Issue(VoucherSyncIssueKind.COUPON_EXHAUSTED, "coupon:" + voucher.getExternalCouponId(),
                    "single-use coupon " + voucher.getExternalCouponId() + " (" + voucher.maskedCode() + ") was used"
                            + " for " + voucher.getRedeemedAmount() + " of " + voucher.getFaceValue() + " "
                            + voucher.getCurrency() + ": the rest cannot be spent"));
        }
        voucherRepository.saveAndFlush(voucher);

        List<VoucherSyncIssueKind> opened = new ArrayList<>();
        List<AuditLog.AuditLogBuilder> audits = new ArrayList<>();
        for (VoucherSyncIssue issue : upsertIssues(voucher.getId(), seen, snapshot.activity().complete(), now,
                opened)) {
            if (issue.isBlocking()) {
                audits.add(audit(voucher, "VOUCHER_SYNC_ISSUE", "kind:" + issue.getKind() + ";ref:"
                        + issue.getExternalRef() + ";detail:" + issue.getDetail()));
            }
        }
        String merchant = voucher.getMerchant().getMerchantCode();
        for (VoucherRedemption spend : plan.spends()) {
            audits.add(audit(voucher, "VOUCHER_REDEEMED", "merchant:" + merchant + ";reference:"
                    + spend.getMerchantReference() + ";order:" + spend.getExternalOrderNumber() + ";amount:"
                    + spend.getAmount() + " " + voucher.getCurrency() + ";balanceAfter:" + spend.getBalanceAfter()
                    + ";outlet:" + spend.getOutletId() + ";occurredAt:" + spend.getRedeemedAt()));
        }
        for (VoucherRefund refund : plan.refunds()) {
            audits.add(audit(voucher, "VOUCHER_REFUNDED", "merchant:" + merchant + ";reference:"
                    + refund.getMerchantReference() + ";creditMemo:" + refund.getExternalCreditmemoNumber()
                    + ";order:" + refund.getExternalOrderId() + ";amount:" + refund.getAmount() + " "
                    + voucher.getCurrency() + ";balanceAfter:" + refund.getBalanceAfter() + ";occurredAt:"
                    + refund.getRefundedAt()));
        }
        if (stopped != null) {
            audits.add(audit(voucher, "VOUCHER_STOPPED", "reason:" + stopped + ";loanAccount:"
                    + voucher.getLoanAccount() + ";spent:" + voucher.getRedeemedAmount() + " " + voucher.getCurrency()
                    + ";expiresAt:" + voucher.getExpiresAt()));
        }

        boolean changed = !plan.spends().isEmpty() || !plan.refunds().isEmpty() || stopped != null
                || heldBefore.compareTo(voucher.getHeldAmount()) != 0 || statusBefore != voucher.getStatus()
                || withdrawnBefore != (voucher.getCouponWithdrawnAt() != null);
        Outcome outcome = plan.blockedBy() != null ? Outcome.BLOCKED : changed ? Outcome.SYNCED : Outcome.UNCHANGED;
        List<VoucherSyncIssueResponse> open = issueRepository.findByVoucherIdAndClearedAtIsNullOrderByIdAsc(
                voucher.getId()).stream().map(issue -> VoucherSyncIssueResponse.of(issue, voucher.getLoanAccount()))
                .toList();
        GetMoreSyncResult result = new GetMoreSyncResult(outcome, plan.spends().size(), plan.refunds().size(),
                voucher.getHeldAmount(), plan.liveOrdersOnCoupon(), open, voucher.getCouponWithdrawnAt() != null,
                VoucherResponse.of(voucher, now));
        return new Applied(result, audits, opened);
    }

    /**
     * Records the issues seen: a new one is added, one seen before has its last-seen time moved (and reopens if it was
     * cleared). When the snapshot was complete, an open one not seen again is cleared; an incomplete one cannot say
     * what is absent. The same issue seen twice in one sync is one row, with its latest detail.
     *
     * @param opened gets the kind of each issue opened now
     * @return the issues opened now
     */
    private List<VoucherSyncIssue> upsertIssues(Long voucherId, List<Issue> seen, boolean complete, LocalDateTime now,
                                                List<VoucherSyncIssueKind> opened) {
        Map<String, Issue> unique = new LinkedHashMap<>();
        seen.forEach(issue -> unique.put(issue.kind() + "|" + issue.externalRef(), issue));
        Map<String, VoucherSyncIssue> existing = new LinkedHashMap<>();
        issueRepository.findByVoucherIdOrderByIdAsc(voucherId)
                .forEach(row -> existing.put(row.getKind() + "|" + row.getExternalRef(), row));
        List<VoucherSyncIssue> openedNow = new ArrayList<>();
        unique.forEach((key, issue) -> {
            VoucherSyncIssue row = existing.remove(key);
            if (row == null) {
                row = issueRepository.save(VoucherSyncIssue.builder()
                        .voucherId(voucherId)
                        .kind(issue.kind())
                        .blocking(issue.blocking())
                        .externalRef(issue.externalRef())
                        .detail(issue.detail())
                        .firstSeenAt(now)
                        .lastSeenAt(now)
                        .build());
                openedNow.add(row);
            } else {
                boolean reopened = !row.isOpen();
                row.seen(issue.detail(), issue.blocking(), now);
                issueRepository.save(row);
                if (reopened) {
                    openedNow.add(row);
                }
            }
        });
        if (complete) {
            existing.values().stream().filter(VoucherSyncIssue::isOpen).forEach(row -> {
                row.clear(now);
                issueRepository.save(row);
            });
        }
        issueRepository.flush();
        openedNow.forEach(row -> opened.add(row.getKind()));
        return openedNow;
    }

    private Applied nothing(Outcome outcome, Voucher voucher, LocalDateTime now) {
        return new Applied(GetMoreSyncResult.nothing(outcome, VoucherResponse.of(voucher, now)), List.of(),
                List.of());
    }

    private static AuditLog.AuditLogBuilder audit(Voucher voucher, String event, String detail) {
        return AuditLog.builder()
                .eventType(event)
                .entityType(VoucherService.ENTITY).entityId(String.valueOf(voucher.getId()))
                .actorId(voucher.getStoreMode() == GetMoreMode.SIMULATED ? GetMoreSpendPlan.SIMULATOR_ACTOR
                        : GetMoreSpendPlan.SYNC_ACTOR)
                .channelUsed(CHANNEL)
                .detail(detail);
    }
}
