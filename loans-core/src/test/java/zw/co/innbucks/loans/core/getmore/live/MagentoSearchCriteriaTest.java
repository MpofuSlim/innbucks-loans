package zw.co.innbucks.loans.core.getmore.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code searchCriteria} query string: what goes on the wire must decode to exactly the pairs built (the pairs
 * the signer signs), and anything Magento would read differently from what is signed is refused before a request
 * exists.
 */
class MagentoSearchCriteriaTest {

    @Test
    @DisplayName("the encoded wire query decodes to exactly the pairs built, which is what the signer sees")
    void wireQueryDecodesToThePairs() {
        MagentoSearchCriteria.Query query = MagentoSearchCriteria.page(2, 50)
                .likePrefix("coupon_code", "INN-K7Q2M3XW-")
                .gteq("created_at", LocalDateTime.of(2026, 10, 1, 0, 0))
                .lt("created_at", LocalDateTime.of(2026, 10, 20, 0, 0))
                .sortAscending("entity_id")
                .fields(MagentoSearchCriteria.ORDER_FIELDS)
                .toQuery();

        assertThat(query.encoded()).doesNotContain("+", " ", "[", "]", ",", ":")
                .contains("INN-K7Q2M3XW-%25", "2026-10-01%2000%3A00%3A00");
        assertThat(MagentoOAuth1Signer.queryPairs(URI.create("https://h.example/p?" + query.encoded()).getRawQuery()))
                .containsExactlyElementsOf(query.pairs());
        assertThat(query.pairs()).containsExactly(
                Map.entry("searchCriteria[filter_groups][0][filters][0][field]", "coupon_code"),
                Map.entry("searchCriteria[filter_groups][0][filters][0][value]", "INN-K7Q2M3XW-%"),
                Map.entry("searchCriteria[filter_groups][0][filters][0][condition_type]", "like"),
                Map.entry("searchCriteria[filter_groups][1][filters][0][field]", "created_at"),
                Map.entry("searchCriteria[filter_groups][1][filters][0][value]", "2026-10-01 00:00:00"),
                Map.entry("searchCriteria[filter_groups][1][filters][0][condition_type]", "gteq"),
                Map.entry("searchCriteria[filter_groups][2][filters][0][field]", "created_at"),
                Map.entry("searchCriteria[filter_groups][2][filters][0][value]", "2026-10-20 00:00:00"),
                Map.entry("searchCriteria[filter_groups][2][filters][0][condition_type]", "lt"),
                Map.entry("searchCriteria[sortOrders][0][field]", "entity_id"),
                Map.entry("searchCriteria[sortOrders][0][direction]", "ASC"),
                Map.entry("searchCriteria[pageSize]", "50"),
                Map.entry("searchCriteria[currentPage]", "2"),
                Map.entry("fields", MagentoSearchCriteria.ORDER_FIELDS));
    }

    @Test
    @DisplayName("page size 0 or 301 is refused; 1 and 300 are sent; a page below 1 is refused")
    void pageSizeBounds() {
        assertThatThrownBy(() -> MagentoSearchCriteria.page(1, 0).toQuery())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pageSize");
        assertThatThrownBy(() -> MagentoSearchCriteria.page(1, 301).toQuery())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1..300");
        assertThatThrownBy(() -> MagentoSearchCriteria.page(0, 50).toQuery())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currentPage");
        assertThat(MagentoSearchCriteria.page(1, 1).toQuery().encoded()).contains("searchCriteria%5BpageSize%5D=1&");
        assertThat(MagentoSearchCriteria.page(1, 300).toQuery().encoded()).contains("=300&");
    }

    @Test
    @DisplayName("a like value holding '_' (Magento's unescaped wildcard), or '%' before its end, is refused")
    void likeValuesThatMatchMoreThanAsked() {
        assertThatThrownBy(() -> MagentoSearchCriteria.page(1, 50).likePrefix("code", "INN_K7Q2M3XW-").toQuery())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'_'");
        assertThatThrownBy(() -> MagentoSearchCriteria.page(1, 50).likePrefix("code", "INN-%K7-").toQuery())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'%'");
        assertThat(MagentoSearchCriteria.page(1, 50).eq("code", "A_B").toQuery().pairs())
                .as("an eq value may hold '_'").contains(Map.entry(
                        "searchCriteria[filter_groups][0][filters][0][value]", "A_B"));
    }

    @Test
    @DisplayName("11 filter groups, 11 filters in a group or 11 sort orders are refused; 10 of each are sent")
    void indexesStayBelowTen() {
        MagentoSearchCriteria groups = MagentoSearchCriteria.page(1, 50);
        for (int i = 0; i < 10; i++) {
            groups.eq("f" + i, "v");
        }
        assertThat(groups.toQuery().pairs()).contains(Map.entry(
                "searchCriteria[filter_groups][9][filters][0][field]", "f9"));
        groups.eq("f10", "v");
        assertThatThrownBy(groups::toQuery).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filter groups");

        MagentoSearchCriteria.Filter[] eleven = new MagentoSearchCriteria.Filter[11];
        for (int i = 0; i < 11; i++) {
            eleven[i] = new MagentoSearchCriteria.Filter("f" + i, "v", "eq");
        }
        assertThatThrownBy(() -> MagentoSearchCriteria.page(1, 50).group(eleven).toQuery())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("filters");
        assertThat(MagentoSearchCriteria.page(1, 50).group(List.of(eleven).subList(0, 10)
                .toArray(MagentoSearchCriteria.Filter[]::new)).toQuery().pairs())
                .contains(Map.entry("searchCriteria[filter_groups][0][filters][9][field]", "f9"));

        MagentoSearchCriteria sorts = MagentoSearchCriteria.page(1, 50);
        for (int i = 0; i < 11; i++) {
            sorts.sortAscending("s" + i);
        }
        assertThatThrownBy(sorts::toQuery).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort orders");
    }

    @Test
    @DisplayName("an 'in' list is comma-joined; filter groups are numbered in order; the sort order is sent ASC")
    void inListsGroupsAndSort() {
        MagentoSearchCriteria.Query query = MagentoSearchCriteria.page(1, 100)
                .in("order_id", List.of(5012L, 5013L, 5020L))
                .sortAscending("entity_id")
                .toQuery();

        assertThat(query.pairs()).containsSubsequence(
                Map.entry("searchCriteria[filter_groups][0][filters][0][field]", "order_id"),
                Map.entry("searchCriteria[filter_groups][0][filters][0][value]", "5012,5013,5020"),
                Map.entry("searchCriteria[filter_groups][0][filters][0][condition_type]", "in"),
                Map.entry("searchCriteria[sortOrders][0][field]", "entity_id"),
                Map.entry("searchCriteria[sortOrders][0][direction]", "ASC"));
        assertThat(query.encoded()).contains("5012%2C5013%2C5020");
    }

    @Test
    @DisplayName("the fields projection per search is exactly the spec's: nothing about shoppers is asked for")
    void fieldsProjections() {
        assertThat(MagentoSearchCriteria.COUPON_FIELDS).isEqualTo("items[coupon_id,rule_id,code,usage_limit,"
                + "usage_per_customer,times_used,expiration_date,is_primary,created_at,type],total_count");
        assertThat(MagentoSearchCriteria.ORDER_FIELDS).isEqualTo("items[entity_id,increment_id,coupon_code,"
                + "applied_rule_ids,state,order_currency_code,subtotal,grand_total,discount_amount,discount_invoiced,"
                + "discount_refunded,discount_canceled,store_id,store_name,created_at,updated_at],total_count");
        assertThat(MagentoSearchCriteria.CREDIT_MEMO_FIELDS).isEqualTo("items[entity_id,increment_id,order_id,"
                + "state,discount_amount,subtotal,grand_total,created_at],total_count");
        assertThat(MagentoSearchCriteria.ORDER_FIELDS).doesNotContain("customer", "address", "payment", "telephone");
    }

    @Test
    @DisplayName("the query prints its parameter names only: a value can be a code")
    void queryPrintsNamesOnly() {
        MagentoSearchCriteria.Query query = MagentoSearchCriteria.page(1, 50)
                .eq("code", "INN-K7Q2M3XW-7KQ2M9XWD4RT8B1C").toQuery();
        assertThat(query.toString()).doesNotContain("7KQ2M9XWD4RT8B1C").contains("[value]");
    }
}
