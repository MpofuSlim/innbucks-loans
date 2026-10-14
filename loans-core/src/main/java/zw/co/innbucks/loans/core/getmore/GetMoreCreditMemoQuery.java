package zw.co.innbucks.loans.core.getmore;

import java.util.List;

/** A page of {@code GET /V1/creditmemos} for 1 to 100 orders ({@code order_id in a,b,c}). */
public record GetMoreCreditMemoQuery(List<Long> orderIds, int page, int pageSize) {

    public static final int MAX_ORDER_IDS = 100;

    public GetMoreCreditMemoQuery {
        if (orderIds == null || orderIds.isEmpty() || orderIds.size() > MAX_ORDER_IDS) {
            throw new IllegalArgumentException("A credit memo search names 1 to " + MAX_ORDER_IDS + " order ids");
        }
        orderIds = List.copyOf(orderIds);
    }
}
