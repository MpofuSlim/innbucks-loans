package zw.co.innbucks.loans.core.getmore.live.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Magento's search envelope, {@code {"items":[...],"search_criteria":{...},"total_count":N}}. Every search loans sends
 * carries the {@code fields} projection, and Magento's {@code FieldsFilter::applyFilter} renders an EMPTY collection
 * under it as {@code "items": null} (its result starts null and the loop over no items never runs), so an empty page
 * arrives as {@code {"items":null,"total_count":0}}.
 */
public record MagentoSearchResultDto<T>(@JsonProperty("items") List<T> items,
                                        @JsonProperty("total_count") Integer totalCount) {

    /**
     * The page's items: {@code items} as sent, or none when it is absent or null AND {@code total_count} says this page
     * holds nothing (Magento's projection of an empty collection).
     *
     * @throws MagentoShapeException {@code total_count} is missing, or {@code items} is missing while the count says
     *                               this page has some
     */
    public List<T> itemsOn(int currentPage, int pageSize) {
        int total = requiredTotalCount();
        if (items != null) {
            return items;
        }
        if ((long) (currentPage - 1) * pageSize >= total) {
            return List.of();
        }
        throw new MagentoShapeException("missing items");
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
