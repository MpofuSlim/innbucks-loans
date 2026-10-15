package zw.co.innbucks.loans.core.getmore;

import java.util.List;

/**
 * Everything the store holds on one issuance's prefix, as {@link GetMoreCoupons#activity} read it: its coupons, the
 * orders placed with them, and the credit memos of those orders. {@code complete} is false when a list was cut short
 * (more pages than loans reads), so nothing may be concluded from what is absent.
 */
public record CouponActivity(List<GetMoreCoupon> couponsOnPrefix, List<GetMoreOrder> ordersOnPrefix,
                             List<GetMoreCreditMemo> creditMemos, boolean complete) {

    public CouponActivity {
        couponsOnPrefix = List.copyOf(couponsOnPrefix);
        ordersOnPrefix = List.copyOf(ordersOnPrefix);
        creditMemos = List.copyOf(creditMemos);
    }
}
