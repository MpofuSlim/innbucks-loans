package zw.co.innbucks.loans.core.getmore.live;

/**
 * How a LIVE coupon holds a voucher's value ({@code GETMORE_LIVE_AMOUNT_MODEL}). GetMore has not said yet, so the
 * default issues nothing.
 */
public enum LiveAmountModel {
    /** The default: refuse before any generate ({@link NoLiveAmountModel}). */
    NONE,
    /** The shared rule's own {@code cart_fixed} amount, one purchase per coupon ({@link RuleAmountSingleUseBinding}). */
    RULE_AMOUNT_SINGLE_USE
}
