package zw.co.innbucks.loans.core.getmore.simulator;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A refund at the simulated store: the credit memo (state 2, refunded) in Magento's shape, and what the coupon has left
 * once it is given back (assumption A3).
 *
 * @param couponId the coupon the refunded sale was made with
 * @param replayed a refund with this reference already existed and is answered again
 */
public record SimRefund(long creditMemoId, String creditMemoNumber, long orderId, String orderNumber, long couponId,
                        Map<String, Object> creditMemo, BigDecimal amount, String currency, BigDecimal balance,
                        boolean replayed) {
}
