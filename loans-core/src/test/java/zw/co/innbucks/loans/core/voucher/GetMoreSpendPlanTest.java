package zw.co.innbucks.loans.core.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import zw.co.innbucks.loans.core.getmore.CouponActivity;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemo;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;
import zw.co.innbucks.loans.core.voucher.GetMoreSpendPlan.Issue;
import zw.co.innbucks.loans.core.voucher.GetMoreSpendPlan.Plan;
import zw.co.innbucks.loans.core.voucher.GetMoreSpendPlan.Snapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Every money rule of a GetMore sync, table by table and with no store, database or clock: spends by invoiced level,
 * refunds by credit memo, one stream in time order with the right balance after each row, every blocking anomaly
 * stopping the stream at its point and nothing after it, the warnings, held sales, and a snapshot read twice recording
 * nothing twice. The voucher is Rutendo Chari's: 150.00 on coupon 1207 (rule 43), simulated.
 */
class GetMoreSpendPlanTest {

    private static final String CODE = TestVouchers.GETMORE_CODE;
    private static final BigDecimal FACE = new BigDecimal("150.00");
    /** 10:31:02 in Harare on 14 October 2026, the first sale. */
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 14, 8, 31, 2);
    private static final LocalDateTime T2 = T1.plusMinutes(4).plusSeconds(38);
    private static final LocalDateTime T3 = T1.plusMinutes(8).plusSeconds(56);
    private static final LocalDateTime NOW = T1.plusMinutes(10);
    private static final String STORE = "GetMore\nGetMore Avondale\nDefault Store View";

    private final VoucherCodeVault vault = VoucherCodeVaultTest.vault();

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private static Snapshot voucher() {
        return voucher(UnaryOperator.identity());
    }

    private static Snapshot voucher(UnaryOperator<Snapshot> change) {
        return change.apply(new Snapshot(9L, 3L, GetMoreMode.SIMULATED, CODE, 43L, FACE, "USD",
                TestVouchers.GETMORE_EXPIRES_AT, false, new BigDecimal("0.00"), null));
    }

    /** An order with the voucher's code, as Magento signs it: discounts negative, canceled positive. */
    private static GetMoreOrder order(long id, String ordered, String invoiced, LocalDateTime created) {
        return order(id, ordered, invoiced, "0", "0", invoiced.equals(ordered) ? "complete" : "processing", created,
                created);
    }

    private static GetMoreOrder order(long id, String ordered, String invoiced, String refunded, String canceled,
                                      String state, LocalDateTime created, LocalDateTime updated) {
        return new GetMoreOrder(id, String.format("SIM%09d", id), CODE, Set.of(43L), state, "USD",
                new BigDecimal(ordered), BigDecimal.ZERO, new BigDecimal(ordered).negate(),
                new BigDecimal(invoiced).negate(), new BigDecimal(refunded).negate(), new BigDecimal(canceled), 2,
                STORE, created, updated);
    }

    private static GetMoreOrder with(GetMoreOrder o, String code, Set<Long> rules, String currency) {
        return new GetMoreOrder(o.entityId(), o.incrementId(), code, rules, o.state(), currency, o.subtotal(),
                o.grandTotal(), o.discountAmount(), o.discountInvoiced(), o.discountRefunded(), o.discountCanceled(),
                o.storeId(), o.storeName(), o.createdAtUtc(), o.updatedAtUtc());
    }

    private static GetMoreOrder amounts(GetMoreOrder o, String amount, String invoiced, String refunded) {
        return new GetMoreOrder(o.entityId(), o.incrementId(), o.couponCode(), o.appliedRuleIds(), o.state(),
                o.orderCurrencyCode(), o.subtotal(), o.grandTotal(), new BigDecimal(amount), new BigDecimal(invoiced),
                new BigDecimal(refunded), o.discountCanceled(), o.storeId(), o.storeName(), o.createdAtUtc(),
                o.updatedAtUtc());
    }

    private static GetMoreCreditMemo memo(long id, long orderId, String refunded, int state, LocalDateTime at) {
        return new GetMoreCreditMemo(id, String.format("SIMCM%08d", id), orderId, state,
                new BigDecimal(refunded).negate(), new BigDecimal(refunded), BigDecimal.ZERO, at);
    }

    private static CouponActivity activity(List<GetMoreOrder> orders, List<GetMoreCreditMemo> memos) {
        return new CouponActivity(List.of(), orders, memos, true);
    }

    private static Plan plan(Snapshot voucher, List<VoucherRedemption> spends, List<VoucherRefund> refunds,
                             CouponActivity activity) {
        return GetMoreSpendPlan.plan(voucher, spends, refunds, activity, NOW);
    }

    private static Plan plan(CouponActivity activity) {
        return plan(voucher(), List.of(), List.of(), activity);
    }

    /** The voucher's state after the plan, through the entity's own rule (status and the CHECKs' fields). */
    private Voucher applied(Plan plan, Voucher.VoucherBuilder before, LocalDateTime now) {
        Voucher voucher = before.build();
        voucher.applyGetMoreSync(plan.net(), plan.refunded(), plan.held(), plan.lastSpentAt(), plan.lastOutlet(),
                now, plan.blockedBy(), now);
        return voucher;
    }

    private Voucher applied(Plan plan) {
        return applied(plan, TestVouchers.getMoreIssued(vault), NOW);
    }

    // ---- spends -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("one sale: one spend row keyed by its invoiced level, at the store's time, with what was left")
    void oneSpend() {
        Plan plan = plan(activity(List.of(order(12, "45.50", "45.50", T1)), List.of()));

        assertThat(plan.blockedBy()).isNull();
        assertThat(plan.issues()).isEmpty();
        assertThat(plan.net()).isEqualByComparingTo("45.50");
        assertThat(plan.spends()).singleElement().satisfies(row -> {
            assertThat(row.getMerchantReference()).isEqualTo("GMS:O:12:4550");
            assertThat(row.getAmount()).isEqualTo(new BigDecimal("45.50"));
            assertThat(row.getBalanceAfter()).isEqualByComparingTo("104.50");
            assertThat(row.getRedeemedAt()).isEqualTo(T1);
            assertThat(row.getRecordedAt()).isEqualTo(NOW);
            assertThat(row.getOutletId()).isEqualTo("store-2");
            assertThat(row.getOutletName()).isEqualTo("GetMore Avondale");
            assertThat(row.getSource()).isEqualTo(VoucherRedemptionSource.GETMORE_SYNC);
            assertThat(row.getStoreMode()).isEqualTo(GetMoreMode.SIMULATED);
            assertThat(row.getExternalOrderId()).isEqualTo(12L);
            assertThat(row.getExternalOrderNumber()).isEqualTo("SIM000000012");
            assertThat(row.getRedeemedBy()).isEqualTo("getmore-simulator");
            assertThat(row.getVoucherId()).isEqualTo(9L);
            assertThat(row.getMerchantId()).isEqualTo(3L);
        });
        assertThat(plan.lastSpentAt()).isEqualTo(T1);
        assertThat(plan.lastOutlet()).isEqualTo("GetMore Avondale");
        assertThat(applied(plan).getStatus()).isEqualTo(VoucherStatus.PARTIALLY_REDEEMED);
    }

    @Test
    @DisplayName("live: GM: keys and the sync's name; an order with no store is at store-unknown")
    void liveKeys() {
        GetMoreOrder noStore = new GetMoreOrder(12, "000000012", CODE, Set.of(43L), "complete", "USD",
                new BigDecimal("45.50"), BigDecimal.ZERO, new BigDecimal("-45.5000"), new BigDecimal("-45.5000"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, T1, T1);

        Plan plan = plan(voucher(v -> new Snapshot(v.voucherId(), v.merchantId(), GetMoreMode.LIVE, v.code(),
                v.ruleId(), v.faceValue(), v.currency(), v.expiresAt(), false, v.heldAmount(), null)), List.of(),
                List.of(), activity(List.of(noStore), List.of()));

        assertThat(plan.spends()).singleElement().satisfies(row -> {
            assertThat(row.getMerchantReference()).isEqualTo("GM:O:12:4550");
            assertThat(row.getAmount()).isEqualTo(new BigDecimal("45.50"));
            assertThat(row.getRedeemedBy()).isEqualTo("getmore-sync");
            assertThat(row.getOutletId()).isEqualTo("store-unknown");
            assertThat(row.getOutletName()).isNull();
        });
        assertThat(plan.lastOutlet()).isEqualTo("store-unknown");
    }

    @Test
    @DisplayName("spent in parts down to nothing left: PARTIALLY_REDEEMED, then REDEEMED")
    void spendingInParts() {
        Plan first = plan(activity(List.of(order(12, "45.50", "45.50", T1)), List.of()));
        Plan rest = plan(voucher(), first.spends(), List.of(), activity(List.of(order(12, "45.50", "45.50", T1),
                order(13, "104.50", "104.50", T2)), List.of()));

        assertThat(rest.spends()).extracting(VoucherRedemption::getMerchantReference,
                VoucherRedemption::getBalanceAfter).containsExactly(tuple("GMS:O:13:10450", new BigDecimal("0.00")));
        assertThat(rest.net()).isEqualByComparingTo("150.00");
        Voucher voucher = applied(rest);
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.REDEEMED);
        assertThat(voucher.balance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an order invoiced in two steps is two rows, each keyed by its level, the second at the order's update")
    void partialInvoiceIncrements() {
        Plan first = plan(activity(List.of(order(12, "45.50", "20.00", T1)), List.of()));
        assertThat(first.spends()).extracting(VoucherRedemption::getMerchantReference, VoucherRedemption::getAmount)
                .containsExactly(tuple("GMS:O:12:2000", new BigDecimal("20.00")));
        assertThat(first.held()).isEqualByComparingTo("25.50");

        GetMoreOrder invoiced = order(12, "45.50", "45.50", "0", "0", "complete", T1, T2);
        Plan second = plan(voucher(), first.spends(), List.of(), activity(List.of(invoiced), List.of()));

        assertThat(second.spends()).extracting(VoucherRedemption::getMerchantReference, VoucherRedemption::getAmount,
                        VoucherRedemption::getRedeemedAt, VoucherRedemption::getBalanceAfter)
                .containsExactly(tuple("GMS:O:12:4550", new BigDecimal("25.50"), T2, new BigDecimal("104.50")));
        assertThat(second.held()).isEqualByComparingTo("0");
        assertThat(second.net()).isEqualByComparingTo("45.50");
    }

    @Test
    @DisplayName("the same snapshot read twice records nothing the second time")
    void sameSnapshotTwice() {
        CouponActivity activity = activity(List.of(order(12, "45.50", "45.50", T1), order(13, "54.50", "54.50", T2)),
                List.of(memo(3, 12, "10.00", 2, T3)));
        Plan first = plan(activity);

        Plan again = plan(voucher(), first.spends(), first.refunds(), activity);

        assertThat(again.spends()).isEmpty();
        assertThat(again.refunds()).isEmpty();
        assertThat(again.issues()).isEmpty();
        assertThat(again.net()).isEqualByComparingTo(first.net()).isEqualByComparingTo("90.00");
        assertThat(again.refunded()).isEqualByComparingTo("10.00");
        assertThat(again.lastSpentAt()).as("no new spend").isNull();
    }

    // ---- refunds --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("spend A, spend B, refund A, in time order: the right balance after every row (the examples' story)")
    void spendSpendRefundInTimeOrder() {
        Plan plan = plan(activity(List.of(order(12, "45.50", "45.50", "10.00", "0", "complete", T1, T1),
                order(13, "54.50", "54.50", T2)), List.of(memo(3, 12, "10.00", 2, T3))));

        assertThat(plan.spends()).extracting(VoucherRedemption::getMerchantReference,
                        VoucherRedemption::getBalanceAfter)
                .containsExactly(tuple("GMS:O:12:4550", new BigDecimal("104.50")),
                        tuple("GMS:O:13:5450", new BigDecimal("50.00")));
        assertThat(plan.refunds()).singleElement().satisfies(row -> {
            assertThat(row.getMerchantReference()).isEqualTo("GMS:CM:3");
            assertThat(row.getAmount()).isEqualTo(new BigDecimal("10.00"));
            assertThat(row.getBalanceAfter()).isEqualByComparingTo("60.00");
            assertThat(row.getRefundedAt()).isEqualTo(T3);
            assertThat(row.getExternalOrderId()).isEqualTo(12L);
            assertThat(row.getExternalCreditmemoId()).isEqualTo(3L);
            assertThat(row.getExternalCreditmemoNumber()).isEqualTo("SIMCM00000003");
            assertThat(row.getRecordedBy()).isEqualTo("getmore-simulator");
        });
        assertThat(plan.net()).isEqualByComparingTo("90.00");
        assertThat(plan.refunded()).isEqualByComparingTo("10.00");
        assertThat(applied(plan).getStatus()).isEqualTo(VoucherStatus.PARTIALLY_REDEEMED);
    }

    @Test
    @DisplayName("a refund of a fully spent voucher brings it back to PARTIALLY_REDEEMED")
    void refundRestores() {
        Plan spent = plan(activity(List.of(order(12, "150.00", "150.00", T1)), List.of()));
        assertThat(applied(spent).getStatus()).isEqualTo(VoucherStatus.REDEEMED);

        Plan refunded = plan(voucher(), spent.spends(), List.of(), activity(List.of(
                        order(12, "150.00", "150.00", "30.00", "0", "complete", T1, T2)),
                List.of(memo(4, 12, "30.00", 2, T2))));

        assertThat(refunded.net()).isEqualByComparingTo("120.00");
        assertThat(applied(refunded, TestVouchers.getMoreIssued(vault).status(VoucherStatus.REDEEMED)
                .redeemedAmount(FACE).lastRedeemedAt(T1).lastRedeemedOutlet("GetMore Avondale"), NOW).getStatus())
                .isEqualTo(VoucherStatus.PARTIALLY_REDEEMED);
    }

    @Test
    @DisplayName("refunded back to nothing: ISSUED again, the last spend still on record")
    void refundBackToNothing() {
        Plan spent = plan(activity(List.of(order(12, "45.50", "45.50", T1)), List.of()));
        Plan refunded = plan(voucher(), spent.spends(), List.of(), activity(List.of(
                order(12, "45.50", "45.50", "45.50", "0", "closed", T1, T2)), List.of(memo(5, 12, "45.50", 2, T2))));

        assertThat(refunded.net()).isEqualByComparingTo("0");
        assertThat(refunded.refunds()).extracting(VoucherRefund::getBalanceAfter)
                .containsExactly(new BigDecimal("150.00"));
        Voucher voucher = applied(refunded, TestVouchers.getMoreIssued(vault).status(VoucherStatus.PARTIALLY_REDEEMED)
                .redeemedAmount(new BigDecimal("45.50")).lastRedeemedAt(T1).lastRedeemedOutlet("GetMore Avondale"),
                NOW);
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.ISSUED);
        assertThat(voucher.getLastRedeemedAt()).isEqualTo(T1);
        assertThat(voucher.getRefundedAmount()).isEqualByComparingTo("45.50");
    }

    @Test
    @DisplayName("a refund dated before its order's next invoiced level waits for it, then applies")
    void refundBeforeItsSpendWaits() {
        Plan first = plan(activity(List.of(order(12, "60.00", "20.00", T1)), List.of()));
        // 30.00 refunded at T2, but the order's second invoice (to 60.00) is dated T3, after it.
        GetMoreOrder later = order(12, "60.00", "60.00", "30.00", "0", "complete", T1, T3);

        Plan plan = plan(voucher(), first.spends(), List.of(), activity(List.of(later),
                List.of(memo(6, 12, "30.00", 2, T2))));

        assertThat(plan.blockedBy()).isNull();
        assertThat(plan.issues()).isEmpty();
        assertThat(plan.spends()).extracting(VoucherRedemption::getMerchantReference,
                VoucherRedemption::getBalanceAfter).containsExactly(tuple("GMS:O:12:6000", new BigDecimal("90.00")));
        assertThat(plan.refunds()).extracting(VoucherRefund::getBalanceAfter)
                .containsExactly(new BigDecimal("120.00"));
        assertThat(plan.net()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("credit memos open (1) or cancelled (3) are no refund; a recorded one now cancelled is a warning")
    void memoStates() {
        Plan spent = plan(activity(List.of(order(12, "45.50", "45.50", T1)), List.of()));
        Plan plan = plan(voucher(), spent.spends(), List.of(), activity(List.of(order(12, "45.50", "45.50", T1)),
                List.of(memo(7, 12, "10.00", 1, T2), memo(8, 12, "10.00", 3, T2))));
        assertThat(plan.refunds()).isEmpty();
        assertThat(plan.issues()).isEmpty();

        Plan refunded = plan(voucher(), spent.spends(), List.of(), activity(List.of(order(12, "45.50", "45.50", T1)),
                List.of(memo(9, 12, "10.00", 2, T2))));
        Plan reversed = plan(voucher(), spent.spends(), refunded.refunds(), activity(List.of(order(12, "45.50",
                "45.50", T1)), List.of(memo(9, 12, "10.00", 3, T2))));

        assertThat(reversed.refunds()).isEmpty();
        assertThat(reversed.blockedBy()).isNull();
        assertThat(reversed.issues()).extracting(Issue::kind, Issue::externalRef, Issue::blocking)
                .containsExactly(tuple(VoucherSyncIssueKind.MEMO_REVERSED, "creditmemo:9", false));
        assertThat(reversed.net()).as("the recorded refund stands").isEqualByComparingTo("35.50");
    }

    @Test
    @DisplayName("a refund for an order GetMore no longer lists waits, with a warning")
    void refundWithoutSale() {
        Plan plan = plan(activity(List.of(), List.of(memo(10, 99, "10.00", 2, T2))));

        assertThat(plan.refunds()).isEmpty();
        assertThat(plan.blockedBy()).isNull();
        assertThat(plan.issues()).extracting(Issue::kind, Issue::externalRef, Issue::blocking)
                .containsExactly(tuple(VoucherSyncIssueKind.REFUND_WITHOUT_SALE, "creditmemo:10", false));
    }

    // ---- blocking anomalies ---------------------------------------------------------------------------------------

    static Stream<Arguments> blocking() {
        GetMoreOrder good = order(12, "45.50", "45.50", T1);
        return Stream.of(
                Arguments.of("another currency", VoucherSyncIssueKind.CURRENCY_MISMATCH,
                        List.of(with(good, CODE, Set.of(43L), "ZWG")), List.of(), "order:12"),
                Arguments.of("another rule as well", VoucherSyncIssueKind.OTHER_RULES_APPLIED,
                        List.of(with(good, CODE, Set.of(43L, 7L), "USD")), List.of(), "order:12"),
                Arguments.of("a positive invoiced discount", VoucherSyncIssueKind.SIGN,
                        List.of(amounts(good, "-45.50", "45.50", "0")), List.of(), "order:12"),
                Arguments.of("three decimal places", VoucherSyncIssueKind.PRECISION,
                        List.of(amounts(good, "-45.505", "-45.505", "0")), List.of(), "order:12"),
                Arguments.of("more than the voucher (the 100x guard)", VoucherSyncIssueKind.AMOUNT_EXCEEDS_FACE,
                        List.of(order(12, "4550.00", "4550.00", T1)), List.of(), "order:12"),
                Arguments.of("a sale with another coupon on the prefix", VoucherSyncIssueKind.SPEND_ON_OTHER_COUPON,
                        List.of(with(good, TestVouchers.GETMORE_PREFIX + "AAAAAAAAAAAAAAAA", Set.of(43L), "USD")),
                        List.of(), "order:12"),
                Arguments.of("two sales above the face value", VoucherSyncIssueKind.OVERSPENT,
                        List.of(good, order(13, "120.00", "120.00", T2)), List.of(), "order:13"),
                Arguments.of("a refund above its sale", VoucherSyncIssueKind.REFUND_EXCEEDS_SPEND,
                        List.of(good), List.of(memo(11, 12, "60.00", 2, T2)), "creditmemo:11"),
                Arguments.of("a positive credit memo discount", VoucherSyncIssueKind.SIGN,
                        List.of(good), List.of(memo(12, 12, "-10.00", 2, T2)), "creditmemo:12"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("blocking")
    @DisplayName("each blocking anomaly stops the stream at its point")
    void eachBlockingKind(String what, VoucherSyncIssueKind kind, List<GetMoreOrder> orders,
                          List<GetMoreCreditMemo> memos, String ref) {
        Plan plan = plan(activity(orders, memos));

        assertThat(plan.blockedBy()).isEqualTo(kind);
        assertThat(plan.issues()).filteredOn(Issue::blocking).extracting(Issue::kind, Issue::externalRef)
                .containsExactly(tuple(kind, ref));
        assertThat(plan.issues()).allSatisfy(issue -> assertThat(issue.detail()).doesNotContainIgnoringCase(CODE)
                .doesNotContain("AAAAAAAAAAAAAAAA"));
        assertThat(plan.net()).isBetween(BigDecimal.ZERO, FACE);
    }

    @Test
    @DisplayName("less invoiced than already recorded is blocking; nothing recorded is undone")
    void invoicedDecreased() {
        Plan first = plan(activity(List.of(order(12, "45.50", "45.50", T1)), List.of()));

        Plan plan = plan(voucher(), first.spends(), List.of(), activity(List.of(order(12, "45.50", "20.00", T1)),
                List.of()));

        assertThat(plan.blockedBy()).isEqualTo(VoucherSyncIssueKind.INVOICED_DECREASED);
        assertThat(plan.spends()).isEmpty();
        assertThat(plan.net()).isEqualByComparingTo("45.50");
    }

    @Test
    @DisplayName("an overspend freezes at the last clean point: what came before is recorded, nothing after")
    void overspendFreezesAtTheLastCleanPoint() {
        Plan plan = plan(activity(List.of(order(12, "100.00", "100.00", T1), order(13, "60.00", "60.00", T2),
                order(14, "10.00", "10.00", T3)), List.of(memo(13, 12, "5.00", 2, T3))));

        assertThat(plan.blockedBy()).isEqualTo(VoucherSyncIssueKind.OVERSPENT);
        assertThat(plan.spends()).extracting(VoucherRedemption::getExternalOrderId).containsExactly(12L);
        assertThat(plan.refunds()).as("after the stop").isEmpty();
        assertThat(plan.net()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("an anomaly between two good sales: the first recorded, the stream stops there, the third waits")
    void nothingAfterTheBlock() {
        Plan plan = plan(activity(List.of(order(12, "45.50", "45.50", T1),
                with(order(13, "20.00", "20.00", T2), CODE, Set.of(43L), "ZWG"), order(14, "10.00", "10.00", T3)),
                List.of()));

        assertThat(plan.blockedBy()).isEqualTo(VoucherSyncIssueKind.CURRENCY_MISMATCH);
        assertThat(plan.spends()).extracting(VoucherRedemption::getExternalOrderId).containsExactly(12L);
        assertThat(plan.lastSpentAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("a sale or a held sale on a cancelled voucher's code is blocking; it holds nothing")
    void spendOnCancelled() {
        Snapshot cancelled = voucher(v -> new Snapshot(v.voucherId(), v.merchantId(), v.mode(), v.code(), v.ruleId(),
                v.faceValue(), v.currency(), v.expiresAt(), true, v.heldAmount(), null));

        Plan spent = plan(cancelled, List.of(), List.of(), activity(List.of(order(12, "20.00", "20.00", T1)),
                List.of()));
        assertThat(spent.blockedBy()).isEqualTo(VoucherSyncIssueKind.SPEND_ON_CANCELLED);
        assertThat(spent.spends()).isEmpty();

        Plan held = plan(cancelled, List.of(), List.of(), activity(List.of(order(12, "20.00", "0", T1)), List.of()));
        assertThat(held.blockedBy()).isEqualTo(VoucherSyncIssueKind.SPEND_ON_CANCELLED);
        assertThat(held.held()).as("a cancelled voucher holds nothing (the CHECK)").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an incomplete snapshot applies nothing and is blocking")
    void tooManyOrders() {
        Plan plan = plan(voucher(), List.of(), List.of(), new CouponActivity(List.of(),
                List.of(order(12, "45.50", "45.50", T1)), List.of(), false));

        assertThat(plan.blockedBy()).isEqualTo(VoucherSyncIssueKind.TOO_MANY_ORDERS);
        assertThat(plan.issues()).extracting(Issue::kind, Issue::externalRef)
                .containsExactly(tuple(VoucherSyncIssueKind.TOO_MANY_ORDERS, "voucher"));
        assertThat(plan.spends()).isEmpty();
        assertThat(plan.held()).as("as it was").isEqualByComparingTo("0");
    }

    // ---- warnings -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a sale after the voucher expired is recorded (GetMore bills it) and flagged")
    void lateSpend() {
        LocalDateTime late = TestVouchers.GETMORE_EXPIRES_AT.plusHours(2);

        Plan plan = plan(activity(List.of(order(12, "45.50", "45.50", late)), List.of()));

        assertThat(plan.spends()).hasSize(1);
        assertThat(plan.blockedBy()).isNull();
        assertThat(plan.issues()).extracting(Issue::kind, Issue::externalRef, Issue::blocking)
                .containsExactly(tuple(VoucherSyncIssueKind.LATE_SPEND, "order:12", false));
    }

    @Test
    @DisplayName("an EXPIRED voucher fully spent late is REDEEMED: no expired voucher has all of it spent")
    void expiredFullySpentLateIsRedeemed() {
        LocalDateTime afterExpiry = TestVouchers.GETMORE_EXPIRES_AT.plusDays(1);
        Plan plan = GetMoreSpendPlan.plan(voucher(), List.of(), List.of(), activity(List.of(
                order(12, "150.00", "150.00", TestVouchers.GETMORE_EXPIRES_AT.minusHours(1))), List.of()), afterExpiry);

        Voucher voucher = applied(plan, TestVouchers.getMoreIssued(vault).status(VoucherStatus.EXPIRED), afterExpiry);

        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.REDEEMED);
        assertThat(voucher.getRedeemedAmount()).isEqualByComparingTo(voucher.getFaceValue());
    }

    @Test
    @DisplayName("an order recorded and no longer listed: a warning, only when the snapshot is complete")
    void orderVanished() {
        Plan first = plan(activity(List.of(order(12, "45.50", "45.50", T1)), List.of()));

        Plan complete = plan(voucher(), first.spends(), List.of(), activity(List.of(), List.of()));
        assertThat(complete.issues()).extracting(Issue::kind, Issue::externalRef, Issue::blocking)
                .containsExactly(tuple(VoucherSyncIssueKind.ORDER_VANISHED, "order:12", false));
        assertThat(complete.net()).as("nothing is undone").isEqualByComparingTo("45.50");

        Plan cut = plan(voucher(), first.spends(), List.of(), new CouponActivity(List.of(), List.of(), List.of(),
                false));
        assertThat(cut.issues()).extracting(Issue::kind).doesNotContain(VoucherSyncIssueKind.ORDER_VANISHED);
    }

    // ---- held ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("held: ordered and not invoiced, over the voucher's own orders; a cancelled order holds nothing")
    void held() {
        Plan plan = plan(activity(List.of(
                order(12, "45.50", "0", T1),                                            // processing, all held
                order(13, "60.00", "20.00", T2),                                        // partly invoiced
                order(14, "30.00", "0", "0", "30.00", "canceled", T2, T3),              // cancelled
                order(15, "10.00", "0", "0", "4.00", "processing", T3, T3)),            // partly cancelled
                List.of()));

        assertThat(plan.held()).isEqualByComparingTo("91.50");
        assertThat(plan.net()).isEqualByComparingTo("20.00");
        assertThat(plan.liveOrdersOnCoupon()).isEqualTo(3);
        Voucher voucher = applied(plan);
        assertThat(voucher.getHeldAmount()).isEqualByComparingTo("91.50");
        assertThat(voucher.spendableAtStore()).isEqualByComparingTo("38.50");
    }

    @Test
    @DisplayName("held with a fraction of a cent rounds up: a sale held is never shown as nothing held")
    void heldRoundsUp() {
        Plan plan = plan(activity(List.of(amounts(order(12, "45.50", "0", T1), "-0.0010", "0", "0")), List.of()));

        assertThat(plan.blockedBy()).as("an amount Magento never uses").isEqualTo(VoucherSyncIssueKind.PRECISION);
        assertThat(plan.held()).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("the outlet is the store line of Magento's store name, or the whole name on one line")
    void outletNames() {
        assertThat(GetMoreSpendPlan.outletName("GetMore\nGetMore Avondale\nDefault Store View"))
                .isEqualTo("GetMore Avondale");
        assertThat(GetMoreSpendPlan.outletName("Main Website\r\nBorrowdale")).isEqualTo("Main Website Borrowdale");
        assertThat(GetMoreSpendPlan.outletName("  ")).isNull();
        assertThat(GetMoreSpendPlan.outletName("x".repeat(200))).hasSize(120);
    }

    @Test
    @DisplayName("the plan's snapshot prints the code only masked")
    void snapshotMasksTheCode() {
        assertThat(voucher().toString()).contains("****8B1C").doesNotContain("7KQ2M9XW");
        List<Issue> issues = new ArrayList<>(plan(activity(List.of(with(order(12, "45.50", "45.50", T1),
                TestVouchers.GETMORE_PREFIX + "ZZZZZZZZZZZZ9999", Set.of(43L), "USD")), List.of())).issues());
        assertThat(issues).singleElement().satisfies(issue -> assertThat(issue.detail()).contains("****9999")
                .doesNotContain("ZZZZZZZZZZZZ"));
    }
}
