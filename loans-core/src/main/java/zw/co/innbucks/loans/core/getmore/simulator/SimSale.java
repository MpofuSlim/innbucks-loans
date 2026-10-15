package zw.co.innbucks.loans.core.getmore.simulator;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A sale at the simulated store's test till (or its invoicing): the order in Magento's shape (code masked), and what
 * the coupon has left.
 *
 * @param order      the order as Magento's REST answers it, {@code coupon_code} masked
 * @param amount     what this action took off the coupon (a sale) or invoiced (an invoice)
 * @param balance    what is left on the coupon now (4 decimals, as Magento's amounts)
 * @param replayed   a sale with this reference already existed and is answered again
 */
public record SimSale(long orderId, String orderNumber, long couponId, Map<String, Object> order, BigDecimal amount,
                      String currency, BigDecimal balance, boolean replayed) {
}
