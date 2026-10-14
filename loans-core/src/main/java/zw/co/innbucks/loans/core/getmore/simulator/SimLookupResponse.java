package zw.co.innbucks.loans.core.getmore.simulator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The simulated store's own view of one coupon: {@code store} holds the coupon (absent once withdrawn), its rule, and
 * its orders and credit memos ({@code {items, total_count}}), in Magento's shapes with every code masked and Magento's
 * omissions (no {@code discount_invoiced} while nothing is invoiced, and so on). {@code allowance} and
 * {@code balance} are the simulator's (assumption A1), not Magento's.
 */
public record SimLookupResponse(Map<String, Object> store, BigDecimal allowance, String allowanceCurrency,
                                BigDecimal balance, boolean withdrawn, List<String> assumptions) {
}
