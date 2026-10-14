package zw.co.innbucks.loans.core.getmore.live.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponUpdate;
import zw.co.innbucks.loans.core.getmore.live.MagentoTimes;

import java.util.Map;

import static zw.co.innbucks.loans.core.getmore.live.dto.MagentoShapeException.required;

/**
 * Magento's {@code CouponInterface} (coupons/search items, {@code GET} and {@code PUT /V1/coupons/{id}}). An absent key
 * is null here: Magento omits a null field ({@code expiration_date}, usually). {@code is_primary} is the exception,
 * kept as {@code null} on a generated coupon (its getter is declared non-nullable); null and false both read as not
 * primary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MagentoCouponDto(@JsonProperty("coupon_id") Long couponId,
                               @JsonProperty("rule_id") Long ruleId,
                               @JsonProperty("code") String code,
                               @JsonProperty("usage_limit") Integer usageLimit,
                               @JsonProperty("usage_per_customer") Integer usagePerCustomer,
                               @JsonProperty("times_used") Integer timesUsed,
                               @JsonProperty("expiration_date") String expirationDate,
                               @JsonProperty("is_primary") Boolean primary,
                               @JsonProperty("created_at") String createdAt,
                               @JsonProperty("type") Integer type) {

    /**
     * The PUT body, {@code {"coupon":{coupon_id, rule_id, code, type, expiration_date}}}: nothing else, because
     * Magento resets the usage limits from the rule whatever is sent.
     */
    public static Map<String, Object> updateBody(long couponId, GetMoreCouponUpdate update) {
        return Map.of("coupon", new MagentoCouponDto(couponId, update.ruleId(), update.code(), null, null, null,
                update.expirationDateUtc() == null ? null : MagentoTimes.formatUtc(update.expirationDateUtc()),
                null, null, update.type()));
    }

    /**
     * @throws MagentoShapeException when {@code coupon_id}, {@code rule_id}, {@code code} or {@code times_used} is
     *                               missing (the last is what tells a used coupon from an unused one)
     */
    public GetMoreCoupon toCoupon() {
        return new GetMoreCoupon(required(couponId, "coupon_id"), required(ruleId, "rule_id"), required(code, "code"),
                usageLimit, usagePerCustomer, required(timesUsed, "times_used"),
                MagentoTimes.parseUtc(expirationDate), Boolean.TRUE.equals(primary),
                MagentoTimes.parseUtc(createdAt), type);
    }

    /** Never the code. */
    @Override
    public String toString() {
        return "MagentoCouponDto[couponId=" + couponId + ", ruleId=" + ruleId + ", code="
                + GetMoreCodes.maskedCode(code) + ", timesUsed=" + timesUsed + "]";
    }
}
