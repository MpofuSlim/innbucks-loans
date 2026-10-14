package zw.co.innbucks.loans.core.getmore.live.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponSpec;

import java.util.Map;

/**
 * {@code couponSpec} of {@code POST /V1/coupons/generate}. Only fields Magento defines: an unknown nested field is a
 * 400 ({@code "%fieldName" is not supported}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MagentoCouponSpecDto(@JsonProperty("rule_id") long ruleId,
                                   @JsonProperty("format") String format,
                                   @JsonProperty("quantity") int quantity,
                                   @JsonProperty("length") int length,
                                   @JsonProperty("prefix") String prefix) {

    /** The request body: {@code {"couponSpec":{...}}}. */
    public static Map<String, Object> body(GetMoreCouponSpec spec) {
        return Map.of("couponSpec", new MagentoCouponSpecDto(spec.ruleId(), spec.format().wire(), spec.quantity(),
                spec.length(), spec.prefix()));
    }
}
