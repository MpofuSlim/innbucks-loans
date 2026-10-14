package zw.co.innbucks.loans.core.getmore;

import java.util.List;

/**
 * One page of a Magento search: its items, the {@code total_count} Magento reported for the whole search, and which
 * page this was. A page past the end is empty with the true total (Magento does not clamp it).
 */
public record GetMorePage<T>(List<T> items, int totalCount, int page, int pageSize) {

    public GetMorePage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * No page follows this one: it reaches the total Magento reported. Decided by the count alone, never by how many
     * items came back, because a live search drops items client-side (a prefix Magento matched loosely), so a short or
     * even empty page can still have pages after it.
     */
    public boolean last() {
        return pageSize < 1 || (long) page * pageSize >= totalCount;
    }
}
