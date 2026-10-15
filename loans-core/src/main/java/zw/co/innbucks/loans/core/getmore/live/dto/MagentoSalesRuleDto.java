package zw.co.innbucks.loans.core.getmore.live.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;
import zw.co.innbucks.loans.core.getmore.live.MagentoTimes;

import java.math.BigDecimal;

import static zw.co.innbucks.loans.core.getmore.live.dto.MagentoShapeException.required;

/**
 * Magento's {@code RuleInterface} ({@code GET /V1/salesRules/{id}}), trimmed to what decides whether the rule can back
 * a voucher. Conditions, labels, websites and customer groups are ignored.
 */
public record MagentoSalesRuleDto(@JsonProperty("rule_id") Long ruleId,
                                  @JsonProperty("name") String name,
                                  @JsonProperty("is_active") Boolean active,
                                  @JsonProperty("from_date") String fromDate,
                                  @JsonProperty("to_date") String toDate,
                                  @JsonProperty("simple_action") String simpleAction,
                                  @JsonProperty("discount_amount") BigDecimal discountAmount,
                                  @JsonProperty("coupon_type") String couponType,
                                  @JsonProperty("use_auto_generation") Boolean useAutoGeneration,
                                  @JsonProperty("uses_per_coupon") Integer usesPerCoupon,
                                  @JsonProperty("uses_per_customer") Integer usesPerCustomer,
                                  @JsonProperty("stop_rules_processing") Boolean stopRulesProcessing,
                                  @JsonProperty("apply_to_shipping") Boolean applyToShipping) {

    /** @throws MagentoShapeException when a field the voucher decision rests on is missing */
    public GetMoreSalesRule toRule() {
        return new GetMoreSalesRule(required(ruleId, "rule_id"), name, required(active, "is_active"),
                MagentoTimes.parseDate(fromDate), MagentoTimes.parseDate(toDate),
                required(simpleAction, "simple_action"), required(discountAmount, "discount_amount"),
                required(couponType, "coupon_type"), required(useAutoGeneration, "use_auto_generation"),
                required(usesPerCoupon, "uses_per_coupon"), usesPerCustomer == null ? 0 : usesPerCustomer,
                Boolean.TRUE.equals(stopRulesProcessing), Boolean.TRUE.equals(applyToShipping));
    }
}
