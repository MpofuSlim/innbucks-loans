package zw.co.innbucks.loans.core.getmore;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A Magento credit memo ({@code GET /V1/creditmemos}): a refund against one order. {@code discountAmount} is 0 or
 * negative, exactly as sent, and never null (absent becomes zero). {@code state}: 1 open, 2 refunded, 3 cancelled.
 */
public record GetMoreCreditMemo(long entityId, String incrementId, long orderId, int state, BigDecimal discountAmount,
                                BigDecimal subtotal, BigDecimal grandTotal, LocalDateTime createdAtUtc) {

    public GetMoreCreditMemo {
        Objects.requireNonNull(discountAmount, "discountAmount");
        Objects.requireNonNull(subtotal, "subtotal");
        Objects.requireNonNull(grandTotal, "grandTotal");
    }

    /** Only a refunded memo (state 2) gives a coupon's value back. */
    public boolean refunded() {
        return state == 2;
    }
}
