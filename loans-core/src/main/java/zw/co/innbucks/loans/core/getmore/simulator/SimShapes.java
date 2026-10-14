package zw.co.innbucks.loans.core.getmore.simulator;

import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemo;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The simulated store's rows, two ways: as the port's records (what every caller above the port sees, exactly as the
 * live client maps Magento's JSON: absent amounts already zero), and in Magento's own JSON shape for the staging
 * test actions to show, snake_case, {@code yyyy-MM-dd HH:mm:ss} UTC, and with Magento's omissions: no
 * {@code discount_invoiced} while nothing is invoiced, no {@code discount_refunded} while nothing is refunded, no
 * {@code discount_canceled} while it is zero, no null field but {@code is_primary} (Magento renders it null on a
 * generated coupon). Every code in a shape is masked ({@code ****8B1C}).
 */
final class SimShapes {

    private static final DateTimeFormatter MAGENTO_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MAGENTO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private SimShapes() {
    }

    // ---- the port's records ----------------------------------------------------------------------------------

    static GetMoreCoupon toCoupon(SimCoupon c) {
        return new GetMoreCoupon(c.getCouponId(), c.getRuleId(), c.getCode(), c.getUsageLimit(),
                c.getUsagePerCustomer(), c.getTimesUsed(), c.getExpirationDate(), c.isPrimary(), c.getCreatedAt(),
                c.getType());
    }

    static GetMoreSalesRule toRule(SimRule r) {
        return new GetMoreSalesRule(r.getRuleId(), r.getName(), r.isActive(), r.getFromDate(), r.getToDate(),
                r.getSimpleAction(), r.getDiscountAmount(), r.getCouponType(), r.isUseAutoGeneration(),
                r.getUsesPerCoupon(), r.getUsesPerCustomer(), r.isStopRulesProcessing(), r.isApplyToShipping());
    }

    static GetMoreOrder toOrder(SimOrder o) {
        return new GetMoreOrder(o.getEntityId(), o.getIncrementId(), o.getCouponCode(), ruleIds(o.getAppliedRuleIds()),
                o.getState(), o.getOrderCurrencyCode(), o.getSubtotal(), o.getGrandTotal(), o.getDiscountAmount(),
                o.getDiscountInvoiced(), o.getDiscountRefunded(), o.getDiscountCanceled(), o.getStoreId(),
                o.getStoreName(), o.getCreatedAt(), o.getUpdatedAt());
    }

    static GetMoreCreditMemo toCreditMemo(SimCreditMemo m) {
        return new GetMoreCreditMemo(m.getEntityId(), m.getIncrementId(), m.getOrderId(), m.getState(),
                m.getDiscountAmount(), m.getSubtotal(), m.getGrandTotal(), m.getCreatedAt());
    }

    /** {@code "43"} or {@code "43,12"}, as Magento stores {@code applied_rule_ids}. */
    static Set<Long> ruleIds(String appliedRuleIds) {
        if (appliedRuleIds == null || appliedRuleIds.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        Arrays.stream(appliedRuleIds.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .forEach(s -> ids.add(Long.parseLong(s)));
        return ids;
    }

    // ---- Magento's JSON shape --------------------------------------------------------------------------------

    static Map<String, Object> coupon(SimCoupon c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coupon_id", c.getCouponId());
        out.put("rule_id", c.getRuleId());
        out.put("code", GetMoreCodes.maskedCode(c.getCode()));
        putIfPresent(out, "usage_limit", c.getUsageLimit());
        putIfPresent(out, "usage_per_customer", c.getUsagePerCustomer());
        out.put("times_used", c.getTimesUsed());
        putIfPresent(out, "expiration_date", time(c.getExpirationDate()));
        // Magento stores is_primary NULL on every coupon but the rule's primary one (ResourceModel/Coupon::_beforeSave)
        // and declares getIsPrimary() "@return bool", not nullable, so REST keeps the key and renders it null: a
        // generated coupon reads "is_primary": null, never false and never absent.
        out.put("is_primary", c.isPrimary() ? Boolean.TRUE : null);
        out.put("created_at", time(c.getCreatedAt()));
        out.put("type", c.getType());
        return frozen(out);
    }

    /** A coupon as the port answered it (a PUT's answer), in the same shape as {@link #coupon(SimCoupon)}. */
    static Map<String, Object> coupon(GetMoreCoupon c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coupon_id", c.couponId());
        out.put("rule_id", c.ruleId());
        out.put("code", GetMoreCodes.maskedCode(c.code()));
        putIfPresent(out, "usage_limit", c.usageLimit());
        putIfPresent(out, "usage_per_customer", c.usagePerCustomer());
        out.put("times_used", c.timesUsed());
        putIfPresent(out, "expiration_date", time(c.expirationDateUtc()));
        out.put("is_primary", c.primary() ? Boolean.TRUE : null);
        putIfPresent(out, "created_at", time(c.createdAtUtc()));
        putIfPresent(out, "type", c.type());
        return frozen(out);
    }

    static Map<String, Object> rule(SimRule r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule_id", r.getRuleId());
        out.put("name", r.getName());
        putIfPresent(out, "from_date", date(r.getFromDate()));
        putIfPresent(out, "to_date", date(r.getToDate()));
        out.put("uses_per_customer", r.getUsesPerCustomer());
        out.put("is_active", r.isActive());
        out.put("stop_rules_processing", r.isStopRulesProcessing());
        out.put("simple_action", r.getSimpleAction());
        out.put("discount_amount", r.getDiscountAmount());
        out.put("apply_to_shipping", r.isApplyToShipping());
        out.put("times_used", r.getTimesUsed());
        out.put("coupon_type", r.getCouponType());
        out.put("use_auto_generation", r.isUseAutoGeneration());
        out.put("uses_per_coupon", r.getUsesPerCoupon());
        return frozen(out);
    }

    static Map<String, Object> order(SimOrder o) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entity_id", o.getEntityId());
        out.put("increment_id", o.getIncrementId());
        putIfPresent(out, "coupon_code", GetMoreCodes.maskedCode(o.getCouponCode()));
        putIfPresent(out, "applied_rule_ids", o.getAppliedRuleIds());
        out.put("state", o.getState());
        out.put("status", o.getStatus());
        out.put("order_currency_code", o.getOrderCurrencyCode());
        out.put("base_currency_code", o.getBaseCurrencyCode());
        out.put("subtotal", o.getSubtotal());
        out.put("grand_total", o.getGrandTotal());
        out.put("discount_amount", o.getDiscountAmount());
        putUnlessZero(out, "discount_invoiced", o.getDiscountInvoiced());
        putUnlessZero(out, "discount_refunded", o.getDiscountRefunded());
        putUnlessZero(out, "discount_canceled", o.getDiscountCanceled());
        out.put("store_id", o.getStoreId());
        out.put("store_name", o.getStoreName());
        out.put("created_at", time(o.getCreatedAt()));
        out.put("updated_at", time(o.getUpdatedAt()));
        return frozen(out);
    }

    static Map<String, Object> creditMemo(SimCreditMemo m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entity_id", m.getEntityId());
        out.put("increment_id", m.getIncrementId());
        out.put("order_id", m.getOrderId());
        out.put("state", m.getState());
        out.put("discount_amount", m.getDiscountAmount());
        out.put("subtotal", m.getSubtotal());
        out.put("grand_total", m.getGrandTotal());
        out.put("created_at", time(m.getCreatedAt()));
        return frozen(out);
    }

    /** Magento's search result envelope, without the criteria echo: {@code {items, total_count}}. */
    static Map<String, Object> searchResult(List<Map<String, Object>> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", List.copyOf(items));
        out.put("total_count", items.size());
        return frozen(out);
    }

    static String time(LocalDateTime utc) {
        return utc == null ? null : MAGENTO_TIME.format(utc);
    }

    private static String date(LocalDate date) {
        return date == null ? null : MAGENTO_DATE.format(date);
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static void putUnlessZero(Map<String, Object> out, String key, BigDecimal value) {
        if (value != null && value.signum() != 0) {
            out.put(key, value);
        }
    }

    static Map<String, Object> frozen(Map<String, Object> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
