package zw.co.innbucks.loans.core.getmore.simulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The simulated store's two renderings: the port's records (absent amounts already zero, as the live mapping makes them)
 * and Magento's own JSON shape for the test actions (snake_case, UTC {@code yyyy-MM-dd HH:mm:ss}, unset discounts left
 * out, codes masked). And the fault queue's semantics.
 */
class SimShapesTest {

    private static final String CODE = "SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 14, 8, 31, 2);

    private static SimOrder held() {
        return SimOrder.builder().entityId(13L).incrementId("SIM000000013").saleReference("AVD-T3-000982")
                .couponCode(CODE).appliedRuleIds("43").storeId(2).storeName("GetMore\nGetMore Avondale\nDefault Store View")
                .state("processing").status("processing").orderCurrencyCode("USD").baseCurrencyCode("USD")
                .subtotal(new BigDecimal("20.0000")).grandTotal(new BigDecimal("0.0000"))
                .discountAmount(new BigDecimal("-20.0000")).discountInvoiced(new BigDecimal("0.0000"))
                .discountRefunded(new BigDecimal("0.0000")).discountCanceled(new BigDecimal("0.0000"))
                .createdAt(AT).updatedAt(AT).build();
    }

    @Test
    @DisplayName("Magento's order shape: nothing invoiced, refunded or cancelled leaves those keys out; the code masked")
    void orderShapeOmitsUnsetDiscounts() {
        Map<String, Object> order = SimShapes.order(held());

        assertThat(order).doesNotContainKeys("discount_invoiced", "discount_refunded", "discount_canceled")
                .containsEntry("coupon_code", "****8B1C")
                .containsEntry("discount_amount", new BigDecimal("-20.0000"))
                .containsEntry("applied_rule_ids", "43")
                .containsEntry("created_at", "2026-10-14 08:31:02")
                .containsEntry("store_name", "GetMore\nGetMore Avondale\nDefault Store View");
        assertThat(order.toString()).doesNotContain("7KQ2M9XWD4RT8B1C");

        SimOrder invoiced = held();
        invoiced.setDiscountInvoiced(new BigDecimal("-20.0000"));
        invoiced.setDiscountRefunded(new BigDecimal("-5.0000"));
        assertThat(SimShapes.order(invoiced)).containsEntry("discount_invoiced", new BigDecimal("-20.0000"))
                .containsEntry("discount_refunded", new BigDecimal("-5.0000")).doesNotContainKey("discount_canceled");
    }

    @Test
    @DisplayName("the port's order: every amount present (zero where Magento leaves it out), the rule ids split")
    void portOrderHasEveryAmount() {
        GetMoreOrder order = SimShapes.toOrder(held());

        assertThat(order.discountInvoiced()).isEqualByComparingTo("0");
        assertThat(order.discountRefunded()).isEqualByComparingTo("0");
        assertThat(order.held()).isEqualByComparingTo("20");
        assertThat(order.appliedRuleIds()).containsExactly(43L);
        assertThat(SimShapes.ruleIds("43, 12")).containsExactly(43L, 12L);
        assertThat(SimShapes.ruleIds(null)).isEmpty();
    }

    @Test
    @DisplayName("Magento's coupon shape: no expiration_date or usage limits while unset; is_primary null on a"
            + " generated coupon (stored NULL, declared non-nullable, so kept as null); the code masked")
    void couponShape() {
        SimCoupon coupon = SimCoupon.builder().couponId(1207L).ruleId(43).code(CODE).timesUsed(2).type(1)
                .createdAt(AT).build();

        Map<String, Object> shape = SimShapes.coupon(coupon);

        assertThat(shape).containsEntry("code", "****8B1C").containsEntry("times_used", 2)
                .containsEntry("is_primary", null).doesNotContainKeys("expiration_date", "usage_limit");
        coupon.setPrimary(true);
        assertThat(SimShapes.coupon(coupon)).containsEntry("is_primary", true);
    }

    @Test
    @DisplayName("faults: each queued one fires once, in order; a write's lost answer comes after it acted; a read's is"
            + " refused before")
    void faultsFireOnceInOrder() {
        SimulatorFaults faults = new SimulatorFaults();
        faults.inject(Operation.GENERATE, SimulatorFaults.FaultKind.UNAVAILABLE, 1);
        faults.inject(Operation.GENERATE, SimulatorFaults.FaultKind.LOSE_RESPONSE, 1);
        faults.inject(Operation.ORDERS, SimulatorFaults.FaultKind.LOSE_RESPONSE, 1);
        faults.inject(Operation.SALES_RULE, SimulatorFaults.FaultKind.REJECTED, 2);

        assertThatThrownBy(() -> faults.before(Operation.GENERATE))
                .isInstanceOf(GetMoreUnavailableException.class);
        SimulatorFaults.FaultKind lost = faults.before(Operation.GENERATE);
        assertThat(lost).isEqualTo(SimulatorFaults.FaultKind.LOSE_RESPONSE);
        assertThatThrownBy(() -> faults.after(Operation.GENERATE, lost))
                .isInstanceOf(GetMoreOutcomeUnknownException.class);
        assertThat(faults.before(Operation.GENERATE)).isNull();

        assertThatThrownBy(() -> faults.before(Operation.ORDERS)).isInstanceOf(GetMoreUnavailableException.class);
        assertThatThrownBy(() -> faults.before(Operation.SALES_RULE)).isInstanceOfSatisfying(
                GetMoreRejectedException.class, e -> assertThat(e.renderedMessage()).isEqualTo("Simulated rejection"));
        assertThat(faults.pending()).containsOnlyKeys(Operation.SALES_RULE);

        faults.clear();
        assertThat(faults.pending()).isEmpty();
        assertThat(faults.before(Operation.SALES_RULE)).isNull();
        assertThatThrownBy(() -> faults.inject(Operation.ORDERS, SimulatorFaults.FaultKind.REJECTED, 11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> faults.inject(Operation.ORDERS, SimulatorFaults.FaultKind.REJECTED, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
