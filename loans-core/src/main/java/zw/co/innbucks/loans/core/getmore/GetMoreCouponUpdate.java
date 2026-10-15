package zw.co.innbucks.loans.core.getmore;

import java.time.LocalDateTime;

/**
 * The body of {@code PUT /V1/coupons/{couponId}}: Magento merges it into the stored coupon and resets the usage limits
 * from the rule. {@code code} must be the coupon's code as stored (a different one renames it).
 */
public record GetMoreCouponUpdate(long ruleId, String code, int type, LocalDateTime expirationDateUtc) {

    /** Never the code: {@code ****8B1C}. */
    @Override
    public String toString() {
        return "GetMoreCouponUpdate[ruleId=" + ruleId + ", code=" + GetMoreCodes.maskedCode(code) + ", type=" + type
                + ", expirationDateUtc=" + expirationDateUtc + "]";
    }
}
