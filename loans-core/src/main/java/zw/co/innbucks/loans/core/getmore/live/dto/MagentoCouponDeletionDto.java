package zw.co.innbucks.loans.core.getmore.live.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /V1/coupons/deleteByCodes}: the body {@code {"codes":[...],"ignoreInvalidCoupons":bool}} and the answer
 * {@code {"failed_items":[...],"missing_items":[...]}} (both always present).
 */
public record MagentoCouponDeletionDto(@JsonProperty("failed_items") List<String> failedItems,
                                       @JsonProperty("missing_items") List<String> missingItems) {

    public static Map<String, Object> body(List<String> codesAsStored, boolean ignoreInvalidCoupons) {
        return Map.of("codes", List.copyOf(codesAsStored), "ignoreInvalidCoupons", ignoreInvalidCoupons);
    }

    /** @throws MagentoShapeException when either list is missing: then nobody knows what was deleted */
    public GetMoreCouponDeletion toDeletion() {
        return new GetMoreCouponDeletion(MagentoShapeException.required(failedItems, "failed_items"),
                MagentoShapeException.required(missingItems, "missing_items"));
    }

    /** Counts only: the lists hold full codes. */
    @Override
    public String toString() {
        return "MagentoCouponDeletionDto[failed=" + (failedItems == null ? null : failedItems.size()) + ", missing="
                + (missingItems == null ? null : missingItems.size()) + "]";
    }
}
