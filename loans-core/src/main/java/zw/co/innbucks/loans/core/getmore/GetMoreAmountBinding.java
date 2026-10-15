package zw.co.innbucks.loans.core.getmore;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How a coupon comes to be worth one voucher's value. A Magento coupon carries no amount (the amount lives on its sales
 * rule), and GetMore has not yet said how a per-loan amount is held, so this one strategy isolates the gap: exactly one
 * implementation is built, by configuration, and everything else runs the same in every mode.
 */
public interface GetMoreAmountBinding {

    /**
     * Before any generate: refuses when this store cannot make a coupon worth {@code faceValue}.
     *
     * @throws GetMoreRejectedException with the reason it cannot
     */
    void precheck(GetMoreSalesRule rule, BigDecimal faceValue, String currency, LocalDate voucherLastDay);

    /**
     * After the coupon is found: binds or verifies its value. Idempotent.
     *
     * @throws GetMoreRejectedException {@code VALUE_UNSUPPORTED} when it is already bound to another value
     */
    void bind(GetMoreCoupon coupon, BigDecimal faceValue, String currency);

    /** Whether a coupon can be spent over several visits; false means one purchase uses it up. */
    boolean partialSpending();

    /** {@code NONE}, {@code RULE_AMOUNT_SINGLE_USE} or {@code SIMULATED_PER_COUPON}. */
    String name();
}
