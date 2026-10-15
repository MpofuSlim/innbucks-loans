package zw.co.innbucks.loans.core.voucher;

import org.apache.commons.lang3.StringUtils;
import zw.co.innbucks.loans.core.getmore.CouponActivity;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemo;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Every money rule of a GetMore sync, and nothing else: what GetMore's orders and credit memos on a voucher's prefix
 * add to what loans has already recorded. Pure: it reads a snapshot and the rows already recorded, and returns the rows
 * to add and what the voucher then stands at. It never undoes a row; a row is only ever added.
 *
 * <ul>
 *   <li><b>Spends.</b> An order placed with the voucher's own code spends what it has INVOICED (Magento's
 *       {@code discount_invoiced}): one row per order per invoiced level, keyed {@code GM:O:<order>:<level in cents>}
 *       ({@code GMS:} when simulated), so an order invoiced in two steps is two rows and a level is recorded once
 *       however often it is read. Ordered but not invoiced is HELD, not spent.</li>
 *   <li><b>Refunds.</b> A credit memo in state 2 gives back what it refunds, once ({@code GM:CM:<memo>}).</li>
 *   <li><b>One stream, in time order.</b> Spends and refunds are applied in the order they happened (a spend before a
 *       refund at the same moment), so what was left after each ({@code balance_after}) is right whatever the
 *       interleaving. A refund dated before its order's next invoiced level waits for it.</li>
 *   <li><b>Stop at the first blocking anomaly.</b> An order that cannot be read as the voucher's (another currency,
 *       another rule, a sign or a precision Magento never uses, more than the voucher is worth, less invoiced than
 *       recorded), a sale with another coupon on the prefix, a spend above the face value or on a cancelled voucher,
 *       or a refund above its order's spend stops the stream THERE: everything before it is recorded, nothing after,
 *       until GetMore's data changes. Nothing is ever rounded or guessed. A warning is recorded and the stream goes
 *       on.</li>
 *   <li><b>An incomplete snapshot applies nothing</b>: what is absent from it cannot be told from what is not
 *       there.</li>
 * </ul>
 * What is spent stays between 0 and the face value by construction.
 */
public final class GetMoreSpendPlan {

    static final String SYNC_ACTOR = "getmore-sync";
    static final String SIMULATOR_ACTOR = "getmore-simulator";
    static final String UNKNOWN_OUTLET = "store-unknown";
    private static final int OUTLET_NAME_LENGTH = 120;
    private static final int NUMBER_LENGTH = 50;
    private static final String CANCELED = "canceled";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    private GetMoreSpendPlan() {
    }

    /**
     * The voucher as the plan needs it.
     *
     * @param code           the voucher's code, decrypted in memory only: an order is the voucher's when placed with it
     * @param heldAmount     held at the last sync, kept when nothing can be applied
     * @param lastRedeemedAt the last spend already on record; a spend applied now moves it only forward
     */
    public record Snapshot(Long voucherId, Long merchantId, GetMoreMode mode, String code, long ruleId,
                           BigDecimal faceValue, String currency, LocalDateTime expiresAt, boolean cancelled,
                           BigDecimal heldAmount, LocalDateTime lastRedeemedAt) {

        public Snapshot {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(faceValue, "faceValue");
        }

        static Snapshot of(Voucher voucher, String code) {
            return new Snapshot(voucher.getId(), voucher.getMerchant().getId(), voucher.getStoreMode(), code,
                    voucher.getExternalRuleId(), voucher.getFaceValue(), voucher.getCurrency(), voucher.getExpiresAt(),
                    voucher.getStatus() == VoucherStatus.CANCELLED, voucher.getHeldAmount(),
                    voucher.getLastRedeemedAt());
        }

        /** Never the code: {@code ****8B1C}. */
        @Override
        public String toString() {
            return "Snapshot[voucherId=" + voucherId + ", mode=" + mode + ", code=" + GetMoreCodes.maskedCode(code)
                    + ", ruleId=" + ruleId + ", faceValue=" + faceValue + " " + currency + ", cancelled=" + cancelled
                    + "]";
        }
    }

    /**
     * Something the plan could not apply ({@link VoucherSyncIssueKind#blocking()}) or applied with a doubt. Its detail
     * never holds a code.
     *
     * @param externalRef {@code order:<id>}, {@code creditmemo:<id>}, {@code coupon:<id>} or {@code voucher}
     */
    public record Issue(VoucherSyncIssueKind kind, String externalRef, String detail) {

        public Issue {
            detail = StringUtils.abbreviate(detail, VoucherSyncIssue.DETAIL_LENGTH);
        }

        public boolean blocking() {
            return kind.blocking();
        }
    }

    /**
     * What the voucher stands at once the new rows are added.
     *
     * @param net                spent less refunded, over every row
     * @param held               ordered at GetMore and not invoiced, over the voucher's own orders not cancelled
     * @param lastSpentAt        the latest spend applied now, when it is later than the one on record; else null
     * @param blockedBy          the blocking issue the stream stopped at; null when it ran to the end
     * @param liveOrdersOnCoupon the voucher's own orders still standing at GetMore, invoiced or not
     */
    public record Plan(BigDecimal net, BigDecimal refunded, BigDecimal held, LocalDateTime lastSpentAt,
                       String lastOutlet, VoucherSyncIssueKind blockedBy, List<Issue> issues,
                       List<VoucherRedemption> spends, List<VoucherRefund> refunds, int liveOrdersOnCoupon) {

        public Plan {
            issues = List.copyOf(issues);
            spends = List.copyOf(spends);
            refunds = List.copyOf(refunds);
        }
    }

    /**
     * @param recordedSpends  every spend row of the voucher
     * @param recordedRefunds every refund row of the voucher
     * @param activity        what GetMore holds on the voucher's prefix
     * @param now             when the rows are recorded
     */
    public static Plan plan(Snapshot voucher, List<VoucherRedemption> recordedSpends,
                            List<VoucherRefund> recordedRefunds, CouponActivity activity, LocalDateTime now) {
        return new Run(voucher, recordedSpends, recordedRefunds, activity, now).plan();
    }

    // ---- the run --------------------------------------------------------------------------------------------------

    private sealed interface Event permits Spend, Refund, Block {
        LocalDateTime time();

        /** Spends (and the anomalies that stand in for one) before refunds at the same moment. */
        int rank();

        long id();
    }

    private record Spend(GetMoreOrder order, BigDecimal amount, BigDecimal level, LocalDateTime time)
            implements Event {
        public int rank() {
            return 0;
        }

        public long id() {
            return order.entityId();
        }
    }

    private record Refund(GetMoreCreditMemo memo, BigDecimal amount, LocalDateTime time) implements Event {
        public int rank() {
            return 1;
        }

        public long id() {
            return memo.entityId();
        }

        long orderId() {
            return memo.orderId();
        }
    }

    private record Block(Issue issue, LocalDateTime time, long id) implements Event {
        public int rank() {
            return 0;
        }
    }

    private enum Outcome { APPLIED, PENDING, EXCEEDS }

    private static final Comparator<Event> IN_TIME = Comparator.comparing(Event::time).thenComparingInt(Event::rank)
            .thenComparingLong(Event::id);

    private static final class Run {

        private final Snapshot voucher;
        private final CouponActivity activity;
        private final LocalDateTime now;
        private final String tag;
        private final String actor;

        private final Map<Long, BigDecimal> spentOnOrder = new HashMap<>();
        private final Map<Long, BigDecimal> refundedOnOrder = new HashMap<>();
        private final Set<Long> recordedMemos = new HashSet<>();
        private final Map<Long, Integer> unappliedSpends = new HashMap<>();
        private final Map<Long, GetMoreOrder> ownOrders = new HashMap<>();
        private final List<Issue> issues = new ArrayList<>();
        private final List<VoucherRedemption> spends = new ArrayList<>();
        private final List<VoucherRefund> refunds = new ArrayList<>();
        private final List<Refund> pending = new ArrayList<>();
        private BigDecimal net = ZERO;
        private BigDecimal refunded = ZERO;
        private LocalDateTime lastSpentAt;
        private String lastOutlet;

        Run(Snapshot voucher, List<VoucherRedemption> recordedSpends, List<VoucherRefund> recordedRefunds,
            CouponActivity activity, LocalDateTime now) {
            this.voucher = voucher;
            this.activity = activity;
            this.now = now;
            boolean simulated = voucher.mode() == GetMoreMode.SIMULATED;
            this.tag = simulated ? "GMS" : "GM";
            this.actor = simulated ? SIMULATOR_ACTOR : SYNC_ACTOR;
            for (VoucherRedemption spend : recordedSpends) {
                net = net.add(spend.getAmount());
                if (spend.getExternalOrderId() != null) {
                    spentOnOrder.merge(spend.getExternalOrderId(), spend.getAmount(), BigDecimal::add);
                }
            }
            for (VoucherRefund refund : recordedRefunds) {
                net = net.subtract(refund.getAmount());
                refunded = refunded.add(refund.getAmount());
                recordedMemos.add(refund.getExternalCreditmemoId());
                refundedOnOrder.merge(refund.getExternalOrderId(), refund.getAmount(), BigDecimal::add);
            }
        }

        Plan plan() {
            List<GetMoreOrder> own = new ArrayList<>();
            Set<Long> otherOrders = new HashSet<>();
            List<Event> events = new ArrayList<>();
            for (GetMoreOrder order : activity.ordersOnPrefix()) {
                if (order.couponCode() != null && order.couponCode().equalsIgnoreCase(voucher.code())) {
                    own.add(order);
                    ownOrders.put(order.entityId(), order);
                } else {
                    otherOrders.add(order.entityId());
                    BigDecimal level = order.discountInvoiced().abs();
                    if (level.signum() > 0 || order.held().signum() > 0) {
                        events.add(new Block(new Issue(VoucherSyncIssueKind.SPEND_ON_OTHER_COUPON, ref(order),
                                "order " + name(order) + " spent " + plain(level) + " (held " + plain(order.held())
                                        + ") with coupon " + GetMoreCodes.maskedCode(order.couponCode())
                                        + ", another coupon on the voucher's prefix"), createdAt(order),
                                order.entityId()));
                    }
                }
            }
            int liveOrders = (int) own.stream()
                    .filter(order -> order.discountAmount().abs().subtract(order.discountCanceled().abs()).signum() > 0)
                    .count();
            if (!activity.complete()) {
                issues.add(new Issue(VoucherSyncIssueKind.TOO_MANY_ORDERS, "voucher", "GetMore holds more orders,"
                        + " coupons or credit memos on the voucher's prefix than one sync reads: nothing was applied"));
                return new Plan(net, refunded, voucher.heldAmount(), null, null,
                        VoucherSyncIssueKind.TOO_MANY_ORDERS, issues, List.of(), List.of(), liveOrders);
            }

            own.sort(Comparator.comparing(this::createdAt).thenComparingLong(GetMoreOrder::entityId));
            for (GetMoreOrder order : own) {
                BigDecimal recorded = spentOnOrder.getOrDefault(order.entityId(), ZERO);
                Issue failed = check(order, recorded);
                if (failed != null) {
                    events.add(new Block(failed, createdAt(order), order.entityId()));
                    continue;
                }
                BigDecimal level = order.discountInvoiced().abs();
                boolean spent = level.compareTo(recorded) > 0;
                if (voucher.cancelled() && (spent || held(order).signum() > 0)) {
                    events.add(new Block(new Issue(VoucherSyncIssueKind.SPEND_ON_CANCELLED, ref(order), "order "
                            + name(order) + " spent " + plain(level.subtract(recorded)) + " and holds "
                            + plain(held(order)) + " on a cancelled voucher"), createdAt(order), order.entityId()));
                    continue;
                }
                if (spent) {
                    events.add(new Spend(order, level.subtract(recorded), level,
                            recorded.signum() == 0 ? createdAt(order) : increasedAt(order)));
                    unappliedSpends.merge(order.entityId(), 1, Integer::sum);
                }
            }
            for (GetMoreCreditMemo memo : activity.creditMemos()) {
                if (!memo.refunded()) {
                    if (memo.state() == 3 && recordedMemos.contains(memo.entityId())) {
                        issues.add(new Issue(VoucherSyncIssueKind.MEMO_REVERSED, memoRef(memo), "credit memo "
                                + name(memo) + ", recorded as a refund, is now cancelled at GetMore"));
                    }
                    continue;
                }
                if (recordedMemos.contains(memo.entityId()) || otherOrders.contains(memo.orderId())
                        || memo.discountAmount().signum() == 0) {
                    continue;
                }
                LocalDateTime at = memo.createdAtUtc() == null ? now : memo.createdAtUtc();
                if (memo.discountAmount().signum() > 0) {
                    events.add(new Block(new Issue(VoucherSyncIssueKind.SIGN, memoRef(memo), "credit memo "
                            + name(memo) + " has a positive discount (" + memo.discountAmount().toPlainString()
                            + "): Magento's are negative"), at, memo.entityId()));
                } else if (!twoPlaces(memo.discountAmount())) {
                    events.add(new Block(new Issue(VoucherSyncIssueKind.PRECISION, memoRef(memo), "credit memo "
                            + name(memo) + " refunds " + memo.discountAmount().abs().toPlainString()
                            + ", in more than 2 decimal places: never rounded"), at, memo.entityId()));
                } else {
                    events.add(new Refund(memo, memo.discountAmount().abs(), at));
                }
            }

            events.sort(IN_TIME);
            VoucherSyncIssueKind blockedBy = null;
            for (Event event : events) {
                Issue stop = switch (event) {
                    case Block block -> block.issue();
                    case Spend spend -> apply(spend);
                    case Refund refund -> refund(refund);
                };
                if (stop != null) {
                    issues.add(stop);
                    blockedBy = stop.kind();
                    break;
                }
            }
            if (blockedBy == null) {
                for (Refund refund : pending) {
                    issues.add(new Issue(VoucherSyncIssueKind.REFUND_WITHOUT_SALE, memoRef(refund.memo()),
                            "credit memo " + name(refund.memo()) + " refunds " + plain(refund.amount()) + " of order "
                                    + refund.orderId() + ", which GetMore does not list with a spend that covers it;"
                                    + " it waits for a later sync"));
                }
            }
            Set<Long> listed = new HashSet<>();
            activity.ordersOnPrefix().forEach(order -> listed.add(order.entityId()));
            spentOnOrder.keySet().stream().filter(order -> !listed.contains(order)).sorted()
                    .forEach(order -> issues.add(new Issue(VoucherSyncIssueKind.ORDER_VANISHED, "order:" + order,
                            "order " + order + ", recorded as spending " + plain(spentOnOrder.get(order))
                                    + ", is no longer listed by GetMore")));
            BigDecimal held = ZERO;
            if (!voucher.cancelled()) {
                for (GetMoreOrder order : own) {
                    held = held.add(held(order));
                }
            }
            return new Plan(net, refunded, held, lastSpentAt, lastOutlet, blockedBy, issues, spends, refunds,
                    liveOrders);
        }

        /** The first blocking reason an own order cannot be read as the voucher's, or null. */
        private Issue check(GetMoreOrder order, BigDecimal recorded) {
            String ref = ref(order);
            String name = name(order);
            if (order.orderCurrencyCode() == null || !order.orderCurrencyCode().equalsIgnoreCase(voucher.currency())) {
                return new Issue(VoucherSyncIssueKind.CURRENCY_MISMATCH, ref, "order " + name + " is in "
                        + order.orderCurrencyCode() + ", the voucher in " + voucher.currency());
            }
            BigDecimal level = order.discountInvoiced().abs();
            if (level.signum() > 0 && !order.appliedRuleIds().equals(Set.of(voucher.ruleId()))) {
                return new Issue(VoucherSyncIssueKind.OTHER_RULES_APPLIED, ref, "order " + name + " applied rules "
                        + order.appliedRuleIds().stream().sorted().toList() + ", not rule " + voucher.ruleId()
                        + " alone: its discount cannot be read as the voucher's");
            }
            if (order.discountInvoiced().signum() > 0 || order.discountRefunded().signum() > 0
                    || order.discountAmount().signum() > 0) {
                return new Issue(VoucherSyncIssueKind.SIGN, ref, "order " + name + " has a positive discount (amount "
                        + order.discountAmount().toPlainString() + ", invoiced "
                        + order.discountInvoiced().toPlainString() + ", refunded "
                        + order.discountRefunded().toPlainString() + "): Magento's are negative");
            }
            if (!twoPlaces(order.discountAmount()) || !twoPlaces(order.discountInvoiced())
                    || !twoPlaces(order.discountCanceled()) || !twoPlaces(order.discountRefunded())) {
                return new Issue(VoucherSyncIssueKind.PRECISION, ref, "order " + name + " has an amount in more than"
                        + " 2 decimal places (amount " + order.discountAmount().toPlainString() + ", invoiced "
                        + order.discountInvoiced().toPlainString() + ", canceled "
                        + order.discountCanceled().toPlainString() + "): never rounded");
            }
            if (level.compareTo(voucher.faceValue()) > 0) {
                return new Issue(VoucherSyncIssueKind.AMOUNT_EXCEEDS_FACE, ref, "order " + name + " invoiced "
                        + plain(level) + ", more than the voucher's " + plain(voucher.faceValue()));
            }
            if (level.compareTo(recorded) < 0) {
                return new Issue(VoucherSyncIssueKind.INVOICED_DECREASED, ref, "order " + name + " now shows "
                        + plain(level) + " invoiced, less than the " + plain(recorded) + " already recorded");
            }
            return null;
        }

        /** Records a spend, or the reason the stream stops at it. */
        private Issue apply(Spend spend) {
            GetMoreOrder order = spend.order();
            if (voucher.cancelled()) {
                return new Issue(VoucherSyncIssueKind.SPEND_ON_CANCELLED, ref(order), "order " + name(order)
                        + " spent " + plain(spend.amount()) + " on a cancelled voucher");
            }
            if (net.add(spend.amount()).compareTo(voucher.faceValue()) > 0) {
                return new Issue(VoucherSyncIssueKind.OVERSPENT, ref(order), "order " + name(order) + " spent "
                        + plain(spend.amount()) + ", which would take the voucher to " + plain(net.add(spend.amount()))
                        + " spent of " + plain(voucher.faceValue()));
            }
            BigDecimal amount = money(spend.amount());
            net = net.add(amount);
            spentOnOrder.merge(order.entityId(), amount, BigDecimal::add);
            unappliedSpends.merge(order.entityId(), -1, Integer::sum);
            String outletId = order.storeId() == null ? UNKNOWN_OUTLET : "store-" + order.storeId();
            String outletName = outletName(order.storeName());
            spends.add(VoucherRedemption.builder()
                    .voucherId(voucher.voucherId())
                    .merchantId(voucher.merchantId())
                    .merchantReference(tag + ":O:" + order.entityId() + ":" + cents(spend.level()))
                    .amount(amount)
                    .balanceAfter(voucher.faceValue().subtract(net))
                    .outletId(outletId)
                    .outletName(outletName)
                    .redeemedBy(actor)
                    .redeemedAt(spend.time())
                    .source(VoucherRedemptionSource.GETMORE_SYNC)
                    .storeMode(voucher.mode())
                    .externalOrderId(order.entityId())
                    .externalOrderNumber(StringUtils.abbreviate(order.incrementId(), NUMBER_LENGTH))
                    .recordedAt(now)
                    .build());
            if (voucher.expiresAt() != null && spend.time().isAfter(voucher.expiresAt())) {
                issues.add(new Issue(VoucherSyncIssueKind.LATE_SPEND, ref(order), "order " + name(order) + " spent "
                        + plain(amount) + " at " + spend.time() + " UTC, after the voucher expired at "
                        + voucher.expiresAt() + " UTC: recorded, since GetMore bills it"));
            }
            if (voucher.lastRedeemedAt() == null || spend.time().isAfter(voucher.lastRedeemedAt())) {
                if (lastSpentAt == null || !spend.time().isBefore(lastSpentAt)) {
                    lastSpentAt = spend.time();
                    lastOutlet = outletName == null ? outletId : outletName;
                }
            }
            for (Iterator<Refund> waiting = pending.iterator(); waiting.hasNext(); ) {
                Refund refund = waiting.next();
                Outcome outcome = tryRefund(refund);
                if (outcome == Outcome.APPLIED) {
                    waiting.remove();
                } else if (outcome == Outcome.EXCEEDS) {
                    return exceeds(refund);
                }
            }
            return null;
        }

        /** Records a refund, keeps it waiting for its order's next spend, or gives the reason the stream stops. */
        private Issue refund(Refund refund) {
            Outcome outcome = tryRefund(refund);
            if (outcome == Outcome.PENDING) {
                pending.add(refund);
            }
            return outcome == Outcome.EXCEEDS ? exceeds(refund) : null;
        }

        private Outcome tryRefund(Refund refund) {
            long orderId = refund.orderId();
            BigDecimal spent = spentOnOrder.getOrDefault(orderId, ZERO);
            BigDecimal given = refundedOnOrder.getOrDefault(orderId, ZERO);
            if (spent.compareTo(given.add(refund.amount())) >= 0) {
                BigDecimal amount = money(refund.amount());
                net = net.subtract(amount);
                refunded = refunded.add(amount);
                refundedOnOrder.merge(orderId, amount, BigDecimal::add);
                refunds.add(VoucherRefund.builder()
                        .voucherId(voucher.voucherId())
                        .merchantId(voucher.merchantId())
                        .merchantReference(tag + ":CM:" + refund.memo().entityId())
                        .storeMode(voucher.mode())
                        .externalOrderId(orderId)
                        .externalCreditmemoId(refund.memo().entityId())
                        .externalCreditmemoNumber(StringUtils.abbreviate(refund.memo().incrementId(), NUMBER_LENGTH))
                        .amount(amount)
                        .balanceAfter(voucher.faceValue().subtract(net))
                        .refundedAt(refund.time())
                        .recordedAt(now)
                        .recordedBy(actor)
                        .build());
                return Outcome.APPLIED;
            }
            boolean spendToCome = unappliedSpends.getOrDefault(orderId, 0) > 0;
            return spendToCome || !ownOrders.containsKey(orderId) ? Outcome.PENDING : Outcome.EXCEEDS;
        }

        private Issue exceeds(Refund refund) {
            long orderId = refund.orderId();
            return new Issue(VoucherSyncIssueKind.REFUND_EXCEEDS_SPEND, memoRef(refund.memo()), "credit memo "
                    + name(refund.memo()) + " refunds " + plain(refund.amount()) + " of order " + orderId
                    + ", more than its " + plain(spentOnOrder.getOrDefault(orderId, ZERO)) + " spent less "
                    + plain(refundedOnOrder.getOrDefault(orderId, ZERO)) + " already refunded");
        }

        private BigDecimal held(GetMoreOrder order) {
            if (CANCELED.equalsIgnoreCase(order.state())) {
                return ZERO;
            }
            // Ordered and not invoiced: rounded UP to the cent, so a fraction held is never shown as nothing held.
            return order.held().setScale(2, RoundingMode.UP);
        }

        private LocalDateTime createdAt(GetMoreOrder order) {
            return order.createdAtUtc() == null ? now : order.createdAtUtc();
        }

        /** When a later invoiced level was reached: Magento keeps no time per invoice on the order, so its update. */
        private LocalDateTime increasedAt(GetMoreOrder order) {
            LocalDateTime created = createdAt(order);
            LocalDateTime updated = order.updatedAtUtc();
            return updated == null || updated.isBefore(created) ? created : updated;
        }
    }

    /** {@code GetMore\nAvondale\nDefault Store View}: the middle line, the store; otherwise the whole, on one line. */
    static String outletName(String storeName) {
        if (StringUtils.isBlank(storeName)) {
            return null;
        }
        String[] lines = storeName.strip().split("\\R");
        String name = lines.length >= 3 ? lines[1].strip() : String.join(" ", lines).strip();
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(name, storeName.strip()), OUTLET_NAME_LENGTH);
    }

    private static boolean twoPlaces(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() <= 2;
    }

    /** An amount already checked to two places, at the columns' scale. */
    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static long cents(BigDecimal level) {
        return level.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() <= 2 ? amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                : amount.toPlainString();
    }

    private static String ref(GetMoreOrder order) {
        return "order:" + order.entityId();
    }

    private static String memoRef(GetMoreCreditMemo memo) {
        return "creditmemo:" + memo.entityId();
    }

    private static String name(GetMoreOrder order) {
        return StringUtils.defaultIfBlank(order.incrementId(), "#" + order.entityId());
    }

    private static String name(GetMoreCreditMemo memo) {
        return StringUtils.defaultIfBlank(memo.incrementId(), "#" + memo.entityId());
    }
}
