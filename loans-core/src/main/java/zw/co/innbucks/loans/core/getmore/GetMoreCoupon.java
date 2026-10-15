package zw.co.innbucks.loans.core.getmore;

import java.time.LocalDateTime;

/**
 * One Magento coupon ({@code salesrule_coupon}), as {@code GET /V1/coupons/...} answers it. {@code timesUsed} is a
 * count of orders placed with it (never an amount); {@code expirationDateUtc} is stored but not enforced by Magento at
 * checkout. Timestamps are UTC.
 */
public record GetMoreCoupon(long couponId, long ruleId, String code, Integer usageLimit, Integer usagePerCustomer,
                            int timesUsed, LocalDateTime expirationDateUtc, boolean primary,
                            LocalDateTime createdAtUtc, Integer type) {

    /** Never the code: {@code ****8B1C}. */
    @Override
    public String toString() {
        return "GetMoreCoupon[couponId=" + couponId + ", ruleId=" + ruleId + ", code=" + GetMoreCodes.maskedCode(code)
                + ", usageLimit=" + usageLimit + ", usagePerCustomer=" + usagePerCustomer + ", timesUsed=" + timesUsed
                + ", expirationDateUtc=" + expirationDateUtc + ", primary=" + primary + ", createdAtUtc="
                + createdAtUtc + ", type=" + type + "]";
    }
}
