package zw.co.innbucks.loans.core.getmore.live.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;
import zw.co.innbucks.loans.core.getmore.live.MagentoTimes;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

import static zw.co.innbucks.loans.core.getmore.live.dto.MagentoShapeException.required;

/**
 * Magento's {@code OrderInterface} ({@code GET /V1/orders} items), trimmed to what says what a coupon spent. Magento
 * leaves {@code discount_invoiced}, {@code discount_refunded} and {@code discount_canceled} NULL until an invoice,
 * credit memo or cancellation exists, and REST omits nulls: {@link #toOrder()} makes every absent amount zero.
 */
public record MagentoOrderDto(@JsonProperty("entity_id") Long entityId,
                              @JsonProperty("increment_id") String incrementId,
                              @JsonProperty("coupon_code") String couponCode,
                              @JsonProperty("applied_rule_ids") String appliedRuleIds,
                              @JsonProperty("state") String state,
                              @JsonProperty("order_currency_code") String orderCurrencyCode,
                              @JsonProperty("subtotal") BigDecimal subtotal,
                              @JsonProperty("grand_total") BigDecimal grandTotal,
                              @JsonProperty("discount_amount") BigDecimal discountAmount,
                              @JsonProperty("discount_invoiced") BigDecimal discountInvoiced,
                              @JsonProperty("discount_refunded") BigDecimal discountRefunded,
                              @JsonProperty("discount_canceled") BigDecimal discountCanceled,
                              @JsonProperty("store_id") Integer storeId,
                              @JsonProperty("store_name") String storeName,
                              @JsonProperty("created_at") String createdAt,
                              @JsonProperty("updated_at") String updatedAt) {

    /** @throws MagentoShapeException when {@code entity_id} is missing or {@code applied_rule_ids} is not a list of ids */
    public GetMoreOrder toOrder() {
        return new GetMoreOrder(required(entityId, "entity_id"), incrementId, couponCode, ruleIds(appliedRuleIds),
                state, orderCurrencyCode, zeroIfAbsent(subtotal), zeroIfAbsent(grandTotal),
                zeroIfAbsent(discountAmount), zeroIfAbsent(discountInvoiced), zeroIfAbsent(discountRefunded),
                zeroIfAbsent(discountCanceled), storeId, storeName, MagentoTimes.parseUtc(createdAt),
                MagentoTimes.parseUtc(updatedAt));
    }

    /** {@code "43"} or {@code "43,12"}: a comma-separated string, never an array. */
    static Set<Long> ruleIds(String applied) {
        Set<Long> ids = new LinkedHashSet<>();
        if (applied == null) {
            return ids;
        }
        for (String part : applied.split(",")) {
            String id = part.strip();
            if (id.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(id));
            } catch (NumberFormatException e) {
                throw new MagentoShapeException("applied_rule_ids is not a list of rule ids");
            }
        }
        return ids;
    }

    static BigDecimal zeroIfAbsent(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    /** Never the coupon code. */
    @Override
    public String toString() {
        return "MagentoOrderDto[entityId=" + entityId + ", incrementId=" + incrementId + ", couponCode="
                + GetMoreCodes.maskedCode(couponCode) + ", state=" + state + "]";
    }
}
