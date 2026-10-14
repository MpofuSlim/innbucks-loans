package zw.co.innbucks.loans.core.getmore.live;

import zw.co.innbucks.loans.core.getmore.GetMoreCodes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Magento's {@code searchCriteria} query string, built from decoded pairs and encoded with {@link Rfc3986} (never
 * {@code +}, never a raw space, {@code [} as {@code %5B}, {@code %} as {@code %25}), so the pairs the signer decodes
 * from the wire are exactly the pairs built here.
 *
 * <p>Every filter goes in its own {@code filter_groups[g]} (Magento ANDs groups and ORs the filters inside one). A
 * page size of 1..300 and a page from 1 are always sent. Refused before any network call, when {@link #toQuery()} is
 * asked for it:
 * <ul>
 *   <li>a page size outside 1..300, a page below 1;</li>
 *   <li>a {@code like} value containing {@code _} (Magento's one-character wildcard, which it does not escape) or a
 *       {@code %} anywhere but at its end;</li>
 *   <li>more than 10 filter groups, 10 filters in a group or 10 sort orders: past index 9 Magento's natural sort
 *       orders {@code [10]} after {@code [2]} while the signer's byte order puts it before, so the signatures would
 *       disagree.</li>
 * </ul>
 */
public final class MagentoSearchCriteria {

    public static final int MAX_PAGE_SIZE = 300;
    static final int MAX_INDEX = 10;

    /** {@code fields=}: only what loans maps, so shoppers' names, addresses, phones and payments never reach loans. */
    public static final String COUPON_FIELDS = "items[coupon_id,rule_id,code,usage_limit,usage_per_customer,"
            + "times_used,expiration_date,is_primary,created_at,type],total_count";
    public static final String ORDER_FIELDS = "items[entity_id,increment_id,coupon_code,applied_rule_ids,state,"
            + "order_currency_code,subtotal,grand_total,discount_amount,discount_invoiced,discount_refunded,"
            + "discount_canceled,store_id,store_name,created_at,updated_at],total_count";
    public static final String CREDIT_MEMO_FIELDS = "items[entity_id,increment_id,order_id,state,discount_amount,"
            + "subtotal,grand_total,created_at],total_count";

    /** One Magento filter: {@code field condition_type value}. */
    public record Filter(String field, String value, String conditionType) {

        /** An {@code eq} value can be a full coupon code, so it is shown only as {@code ****8B1C}. */
        @Override
        public String toString() {
            return "Filter[field=" + field + ", conditionType=" + conditionType + ", value="
                    + ("eq".equals(conditionType) ? GetMoreCodes.maskedCode(value) : value) + "]";
        }
    }

    /** The decoded pairs, in the order sent, and the encoded query string carrying exactly them. */
    public record Query(List<Map.Entry<String, String>> pairs, String encoded) {
        public Query {
            pairs = List.copyOf(pairs);
        }

        /** The names only: the values can hold a code. */
        @Override
        public String toString() {
            return "Query" + pairs.stream().map(Map.Entry::getKey).collect(Collectors.joining(", ", "[", "]"));
        }
    }

    private final int currentPage;
    private final int pageSize;
    private final List<List<Filter>> groups = new ArrayList<>();
    private final List<String> sortFields = new ArrayList<>();
    private String fields;

    private MagentoSearchCriteria(int currentPage, int pageSize) {
        this.currentPage = currentPage;
        this.pageSize = pageSize;
    }

    public static MagentoSearchCriteria page(int currentPage, int pageSize) {
        return new MagentoSearchCriteria(currentPage, pageSize);
    }

    public int currentPage() {
        return currentPage;
    }

    public int pageSize() {
        return pageSize;
    }

    /** A filter group of its own, holding these filters (ORed). */
    public MagentoSearchCriteria group(Filter... filters) {
        groups.add(List.of(filters));
        return this;
    }

    /** {@code field like '<prefix>%'}. */
    public MagentoSearchCriteria likePrefix(String field, String prefix) {
        return group(new Filter(field, prefix + "%", "like"));
    }

    public MagentoSearchCriteria eq(String field, String value) {
        return group(new Filter(field, value, "eq"));
    }

    /** {@code field in a,b,c}. */
    public MagentoSearchCriteria in(String field, List<Long> values) {
        StringJoiner joined = new StringJoiner(",");
        values.forEach(v -> joined.add(String.valueOf(v)));
        return group(new Filter(field, joined.toString(), "in"));
    }

    public MagentoSearchCriteria gteq(String field, LocalDateTime utc) {
        return group(new Filter(field, MagentoTimes.formatUtc(utc), "gteq"));
    }

    public MagentoSearchCriteria lt(String field, LocalDateTime utc) {
        return group(new Filter(field, MagentoTimes.formatUtc(utc), "lt"));
    }

    public MagentoSearchCriteria sortAscending(String field) {
        sortFields.add(field);
        return this;
    }

    /** Magento's standard {@code fields} projection. */
    public MagentoSearchCriteria fields(String projection) {
        this.fields = projection;
        return this;
    }

    /**
     * Validates, then builds the pairs and their encoded query string.
     *
     * @throws IllegalArgumentException for anything listed in the class doc, before any network call
     */
    public Query toQuery() {
        validate();
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            List<Filter> filters = groups.get(g);
            for (int f = 0; f < filters.size(); f++) {
                String at = "searchCriteria[filter_groups][" + g + "][filters][" + f + "]";
                Filter filter = filters.get(f);
                pairs.add(Map.entry(at + "[field]", filter.field()));
                pairs.add(Map.entry(at + "[value]", filter.value()));
                pairs.add(Map.entry(at + "[condition_type]", filter.conditionType()));
            }
        }
        for (int s = 0; s < sortFields.size(); s++) {
            pairs.add(Map.entry("searchCriteria[sortOrders][" + s + "][field]", sortFields.get(s)));
            pairs.add(Map.entry("searchCriteria[sortOrders][" + s + "][direction]", "ASC"));
        }
        pairs.add(Map.entry("searchCriteria[pageSize]", String.valueOf(pageSize)));
        pairs.add(Map.entry("searchCriteria[currentPage]", String.valueOf(currentPage)));
        if (fields != null) {
            pairs.add(Map.entry("fields", fields));
        }
        StringJoiner encoded = new StringJoiner("&");
        for (Map.Entry<String, String> pair : pairs) {
            encoded.add(Rfc3986.encode(pair.getKey()) + "=" + Rfc3986.encode(pair.getValue()));
        }
        return new Query(pairs, encoded.toString());
    }

    private void validate() {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("searchCriteria pageSize must be 1.." + MAX_PAGE_SIZE + ", was "
                    + pageSize);
        }
        if (currentPage < 1) {
            throw new IllegalArgumentException("searchCriteria currentPage counts from 1, was " + currentPage);
        }
        if (groups.size() > MAX_INDEX) {
            throw new IllegalArgumentException("At most " + MAX_INDEX + " filter groups, was " + groups.size());
        }
        if (sortFields.size() > MAX_INDEX) {
            throw new IllegalArgumentException("At most " + MAX_INDEX + " sort orders, was " + sortFields.size());
        }
        for (List<Filter> filters : groups) {
            if (filters.isEmpty() || filters.size() > MAX_INDEX) {
                throw new IllegalArgumentException("A filter group holds 1 to " + MAX_INDEX + " filters, was "
                        + filters.size());
            }
            for (Filter filter : filters) {
                if (isBlank(filter.field()) || filter.value() == null || isBlank(filter.conditionType())) {
                    throw new IllegalArgumentException("A filter needs a field, a value and a condition type");
                }
                if ("like".equals(filter.conditionType())) {
                    String value = filter.value();
                    int firstPercent = value.indexOf('%');
                    if (value.indexOf('_') >= 0) {
                        throw new IllegalArgumentException("A like value cannot contain '_': Magento reads it as a "
                                + "one-character wildcard");
                    }
                    if (firstPercent >= 0 && firstPercent != value.length() - 1) {
                        throw new IllegalArgumentException("A like value may hold '%' only as its last character");
                    }
                }
            }
        }
        for (String field : sortFields) {
            if (isBlank(field)) {
                throw new IllegalArgumentException("A sort order needs a field");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
