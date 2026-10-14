package zw.co.innbucks.loans.core.getmore;

import java.util.List;

/**
 * {@code GETMORE_MODE=OFF}, the default: every call is refused as {@link GetMoreUnavailableException.Reason#OFF}
 * before anything happens. Nothing above the port calls it while the mode is OFF; this is the backstop.
 */
public final class DisabledGetMoreStore implements GetMoreStore {

    @Override
    public GetMoreMode mode() {
        return GetMoreMode.OFF;
    }

    @Override
    public List<String> generateCoupons(GetMoreCouponSpec spec) {
        throw off(GetMoreException.Operation.GENERATE);
    }

    @Override
    public GetMorePage<GetMoreCoupon> searchCoupons(GetMoreCouponQuery query) {
        throw off(GetMoreException.Operation.SEARCH_COUPONS);
    }

    @Override
    public GetMoreCoupon coupon(long couponId) {
        throw off(GetMoreException.Operation.GET_COUPON);
    }

    @Override
    public GetMoreCoupon updateCoupon(long couponId, GetMoreCouponUpdate update) {
        throw off(GetMoreException.Operation.UPDATE_COUPON);
    }

    @Override
    public GetMoreCouponDeletion deleteCoupons(List<String> codesAsStored, boolean ignoreInvalidCoupons) {
        throw off(GetMoreException.Operation.DELETE_COUPONS);
    }

    @Override
    public GetMoreSalesRule salesRule(long ruleId) {
        throw off(GetMoreException.Operation.SALES_RULE);
    }

    @Override
    public GetMorePage<GetMoreOrder> orders(GetMoreOrderQuery query) {
        throw off(GetMoreException.Operation.ORDERS);
    }

    @Override
    public GetMorePage<GetMoreCreditMemo> creditMemos(GetMoreCreditMemoQuery query) {
        throw off(GetMoreException.Operation.CREDIT_MEMOS);
    }

    private static GetMoreUnavailableException off(GetMoreException.Operation operation) {
        return new GetMoreUnavailableException(operation, GetMoreUnavailableException.Reason.OFF,
                "GETMORE_MODE is OFF");
    }
}
