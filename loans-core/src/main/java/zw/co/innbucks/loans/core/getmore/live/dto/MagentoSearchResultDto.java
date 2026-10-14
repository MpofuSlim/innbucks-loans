package zw.co.innbucks.loans.core.getmore.live.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Magento's search envelope, {@code {"items":[...],"search_criteria":{...},"total_count":N}}. */
public record MagentoSearchResultDto<T>(@JsonProperty("items") List<T> items,
                                        @JsonProperty("total_count") Integer totalCount) {

    /** @throws MagentoShapeException when either key is missing */
    public List<T> requiredItems() {
        return MagentoShapeException.required(items, "items");
    }

    public int requiredTotalCount() {
        return MagentoShapeException.required(totalCount, "total_count");
    }

    /** Counts only: the items may hold codes. */
    @Override
    public String toString() {
        return "MagentoSearchResultDto[items=" + (items == null ? null : items.size()) + ", totalCount="
                + totalCount + "]";
    }
}
