package zw.co.innbucks.loans.core.getmore.simulator;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * What is left on a simulated coupon, from Magento's own rows (assumptions A1 to A3, not Magento):
 * <pre>
 * allowance − Σ over orders not cancelled (|discount_amount| − discount_canceled)
 *           + Σ over refunded (state 2) credit memos |discount_amount|
 * </pre>
 * A sale that is ordered but not yet invoiced counts against the balance, as it does at a real till; a refund gives
 * back what it refunded. Pure: the caller passes the coupon's orders and those orders' memos.
 */
public final class SimulatedBalance {

    private SimulatedBalance() {
    }

    public static BigDecimal of(BigDecimal allowance, Collection<SimOrder> orders, Collection<SimCreditMemo> memos) {
        BigDecimal balance = allowance == null ? BigDecimal.ZERO : allowance;
        for (SimOrder order : orders) {
            if (!SimOrder.CANCELED.equals(order.getState())) {
                balance = balance.subtract(order.getDiscountAmount().abs().subtract(order.getDiscountCanceled().abs()));
            }
        }
        for (SimCreditMemo memo : memos) {
            if (memo.getState() == SimCreditMemo.REFUNDED) {
                balance = balance.add(memo.getDiscountAmount().abs());
            }
        }
        return balance;
    }
}
