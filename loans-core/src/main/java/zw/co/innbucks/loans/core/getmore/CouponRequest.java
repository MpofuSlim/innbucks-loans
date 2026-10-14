package zw.co.innbucks.loans.core.getmore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One voucher's coupon, as {@link GetMoreCoupons#findOrCreate} finds or makes it.
 *
 * @param prefix         the issuance's own prefix, {@code <configured prefix><nonce>-}: only loans' database knows it
 * @param ruleId         the cart price rule the coupon is generated under
 * @param faceValue      what the coupon must be worth
 * @param currency       the voucher's currency
 * @param length         the store's random characters after the prefix
 * @param voucherLastDay the voucher's last market day (the rule must not end before it)
 * @param notBeforeUtc   the adoption floor, the issuance's first claim minus the clock skew: a coupon created earlier
 *                       was not made by loans; null for no floor
 * @param guard          counts the generate before it is sent
 */
public record CouponRequest(String prefix, long ruleId, BigDecimal faceValue, String currency, int length,
                            LocalDate voucherLastDay, LocalDateTime notBeforeUtc, GenerateGuard guard) {

    public CouponRequest {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(faceValue, "faceValue");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(guard, "guard");
        if (prefix.isBlank() || length < 1) {
            throw new IllegalArgumentException("A coupon request needs a prefix and a code length above 0");
        }
    }
}
