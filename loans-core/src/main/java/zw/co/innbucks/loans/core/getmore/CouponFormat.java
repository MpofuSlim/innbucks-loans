package zw.co.innbucks.loans.core.getmore;

import java.util.Locale;

/** The character set of the random part of a generated coupon code: Magento's {@code couponSpec.format}. */
public enum CouponFormat {
    ALPHANUM, ALPHA, NUM;

    /** As Magento's REST API spells it: {@code alphanum}, {@code alpha} or {@code num}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
