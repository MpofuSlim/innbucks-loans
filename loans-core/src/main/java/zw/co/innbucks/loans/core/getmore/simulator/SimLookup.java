package zw.co.innbucks.loans.core.getmore.simulator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The simulated store's own view of one coupon, in Magento's shapes with every code masked: the coupon, its rule, its
 * orders and their credit memos ({@code {items, total_count}} each), plus the simulator's allowance and balance and the
 * assumptions it runs on.
 *
 * @param coupon    null once the coupon is withdrawn (Magento no longer has it; its orders keep the code's text)
 * @param withdrawn the coupon was deleted at the store
 * @param balance   null while no allowance is bound
 */
public record SimLookup(Map<String, Object> coupon, Map<String, Object> rule, Map<String, Object> orders,
                        Map<String, Object> creditmemos, BigDecimal allowance, String allowanceCurrency,
                        BigDecimal balance, boolean withdrawn, List<String> assumptions) {
}
