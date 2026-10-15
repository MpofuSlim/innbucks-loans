package zw.co.innbucks.loans.core.getmore;

import java.util.Objects;

/**
 * Magento's {@code couponSpec} for {@code POST /V1/coupons/generate}: {@code quantity} codes of {@code prefix} followed
 * by {@code length} random characters of {@code format}. Magento validates the numbers (a bad spec is its 400), so this
 * record does not.
 */
public record GetMoreCouponSpec(long ruleId, int quantity, int length, CouponFormat format, String prefix) {

    public GetMoreCouponSpec {
        Objects.requireNonNull(format, "format");
    }

    /** One alphanumeric code under {@code prefix}: what an issuance asks for. */
    public static GetMoreCouponSpec single(long ruleId, String prefix, int length) {
        return new GetMoreCouponSpec(ruleId, 1, length, CouponFormat.ALPHANUM, prefix);
    }
}
