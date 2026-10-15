package zw.co.innbucks.loans.core.getmore.live.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemo;
import zw.co.innbucks.loans.core.getmore.live.MagentoTimes;

import java.math.BigDecimal;

import static zw.co.innbucks.loans.core.getmore.live.dto.MagentoOrderDto.zeroIfAbsent;
import static zw.co.innbucks.loans.core.getmore.live.dto.MagentoShapeException.required;

/** Magento's {@code CreditmemoInterface} ({@code GET /V1/creditmemos} items), trimmed; an absent amount is zero. */
public record MagentoCreditMemoDto(@JsonProperty("entity_id") Long entityId,
                                   @JsonProperty("increment_id") String incrementId,
                                   @JsonProperty("order_id") Long orderId,
                                   @JsonProperty("state") Integer state,
                                   @JsonProperty("discount_amount") BigDecimal discountAmount,
                                   @JsonProperty("subtotal") BigDecimal subtotal,
                                   @JsonProperty("grand_total") BigDecimal grandTotal,
                                   @JsonProperty("created_at") String createdAt) {

    /** @throws MagentoShapeException when {@code entity_id}, {@code order_id} or {@code state} is missing */
    public GetMoreCreditMemo toCreditMemo() {
        return new GetMoreCreditMemo(required(entityId, "entity_id"), incrementId, required(orderId, "order_id"),
                required(state, "state"), zeroIfAbsent(discountAmount), zeroIfAbsent(subtotal),
                zeroIfAbsent(grandTotal), MagentoTimes.parseUtc(createdAt));
    }
}
