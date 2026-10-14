package zw.co.innbucks.loans.core.getmore.simulator;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.voucher.VoucherResponse;

import java.util.List;
import java.util.Map;

/**
 * What a staging test action did, at the store and then in loans: {@code store} is the store's own answer in Magento's
 * shapes with every code masked (the order or credit memo, and what the coupon has left at the store), {@code voucher}
 * the loans voucher after the sync the action ran, and {@code sync} what that sync recorded. Both are absent when the
 * coupon is no voucher's. {@code assumptions} are the simulator's, as this host runs them: none of them is Magento's.
 *
 * @param couponWithdrawn whether the coupon is gone at the store; only for "expire now"
 */
public record SimActionResponse(Map<String, Object> store,
                                @JsonInclude(JsonInclude.Include.NON_NULL) VoucherResponse voucher,
                                @JsonInclude(JsonInclude.Include.NON_NULL) SimSyncSummary sync,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Boolean couponWithdrawn,
                                List<String> assumptions) {
}
