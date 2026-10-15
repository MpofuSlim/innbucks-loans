package zw.co.innbucks.loans.core.getmore;

import java.util.List;

/**
 * What {@code POST /V1/coupons/deleteByCodes} reports: codes it failed to delete, and codes it did not find (a repeat,
 * or a code sent in a different case: Magento compares strings exactly). Every other code sent was deleted.
 */
public record GetMoreCouponDeletion(List<String> failedItems, List<String> missingItems) {

    public GetMoreCouponDeletion {
        failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
        missingItems = missingItems == null ? List.of() : List.copyOf(missingItems);
    }

    /** Counts only: the lists hold full codes. */
    @Override
    public String toString() {
        return "GetMoreCouponDeletion[failed=" + failedItems.size() + ", missing=" + missingItems.size() + "]";
    }
}
