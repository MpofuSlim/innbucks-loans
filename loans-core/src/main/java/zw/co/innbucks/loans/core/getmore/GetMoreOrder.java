package zw.co.innbucks.loans.core.getmore;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * A Magento order ({@code GET /V1/orders}) with the fields that say what a coupon spent. Amounts are exactly as Magento
 * sent them, signs kept: {@code discountAmount}, {@code discountInvoiced} and {@code discountRefunded} are 0 or
 * negative, {@code discountCanceled} is 0 or positive. <b>Never null</b>: Magento omits the invoiced, refunded and
 * canceled amounts until an invoice, credit memo or cancellation exists, and the mapping makes an absent one zero.
 * Timestamps are UTC.
 */
public record GetMoreOrder(long entityId, String incrementId, String couponCode, Set<Long> appliedRuleIds, String state,
                           String orderCurrencyCode, BigDecimal subtotal, BigDecimal grandTotal,
                           BigDecimal discountAmount, BigDecimal discountInvoiced, BigDecimal discountRefunded,
                           BigDecimal discountCanceled, Integer storeId, String storeName,
                           LocalDateTime createdAtUtc, LocalDateTime updatedAtUtc) {

    public GetMoreOrder {
        appliedRuleIds = appliedRuleIds == null ? Set.of() : Set.copyOf(appliedRuleIds);
        Objects.requireNonNull(subtotal, "subtotal");
        Objects.requireNonNull(grandTotal, "grandTotal");
        Objects.requireNonNull(discountAmount, "discountAmount");
        Objects.requireNonNull(discountInvoiced, "discountInvoiced");
        Objects.requireNonNull(discountRefunded, "discountRefunded");
        Objects.requireNonNull(discountCanceled, "discountCanceled");
    }

    /** Ordered at the store but not yet invoiced, so not yet spent: max(0, |amount| − canceled − |invoiced|). */
    public BigDecimal held() {
        BigDecimal held = discountAmount.abs().subtract(discountCanceled.abs()).subtract(discountInvoiced.abs());
        return held.signum() > 0 ? held : BigDecimal.ZERO;
    }

    /** Never the coupon code: {@code ****8B1C}. */
    @Override
    public String toString() {
        return "GetMoreOrder[entityId=" + entityId + ", incrementId=" + incrementId + ", couponCode="
                + GetMoreCodes.maskedCode(couponCode) + ", appliedRuleIds=" + appliedRuleIds + ", state=" + state
                + ", orderCurrencyCode=" + orderCurrencyCode + ", subtotal=" + subtotal + ", grandTotal=" + grandTotal
                + ", discountAmount=" + discountAmount + ", discountInvoiced=" + discountInvoiced
                + ", discountRefunded=" + discountRefunded + ", discountCanceled=" + discountCanceled + ", storeId="
                + storeId + ", storeName=" + storeName + ", createdAtUtc=" + createdAtUtc + ", updatedAtUtc=" + updatedAtUtc + "]";
    }
}
