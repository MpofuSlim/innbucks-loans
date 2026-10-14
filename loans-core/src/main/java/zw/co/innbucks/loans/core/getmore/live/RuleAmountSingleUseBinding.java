package zw.co.innbucks.loans.core.getmore.live;

import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException.Reason;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code GETMORE_LIVE_AMOUNT_MODEL=RULE_AMOUNT_SINGLE_USE}: what vanilla Magento can do. The coupon is worth its rule's
 * {@code cart_fixed} amount, once: one purchase, whatever is left of the amount is lost at that till. So the rule must
 * be exactly right before anything is generated under it, and spending in parts is off.
 *
 * <p>{@link #precheck} requires, in this order: the rule active; auto-generating ({@code use_auto_generation} or
 * {@code coupon_type AUTO}); {@code simple_action cart_fixed}; {@code uses_per_coupon} exactly 1 (above 1 a
 * {@code cart_fixed} coupon gives its full value on every use); {@code discount_amount} equal to the face value;
 * {@code to_date} absent or not before the voucher's last day; the voucher in the store's currency. {@link #bind}
 * checks nothing more: the rule carries the value.
 */
public final class RuleAmountSingleUseBinding implements GetMoreAmountBinding {

    private final String currency;

    /** @param currency the store's currency ({@code GETMORE_LIVE_CURRENCY}) */
    public RuleAmountSingleUseBinding(String currency) {
        this.currency = currency;
    }

    @Override
    public void precheck(GetMoreSalesRule rule, BigDecimal faceValue, String voucherCurrency, LocalDate voucherLastDay) {
        long id = rule.ruleId();
        if (!rule.active()) {
            throw refused(Reason.RULE_NOT_USABLE, "sales rule " + id + " is not active");
        }
        if (!rule.useAutoGeneration() && !"AUTO".equals(rule.couponType())) {
            throw refused(Reason.RULE_NOT_USABLE, "sales rule " + id + " does not generate coupons");
        }
        if (!"cart_fixed".equals(rule.simpleAction())) {
            throw refused(Reason.RULE_NOT_USABLE, "sales rule " + id + " is " + rule.simpleAction()
                    + ", not cart_fixed");
        }
        if (rule.usesPerCoupon() != 1) {
            throw refused(Reason.RULE_NOT_USABLE, "sales rule " + id + " allows " + rule.usesPerCoupon()
                    + " uses per coupon, not 1: a cart_fixed coupon gives its full value on every use");
        }
        if (rule.discountAmount() == null || rule.discountAmount().compareTo(faceValue) != 0) {
            throw refused(Reason.VALUE_UNSUPPORTED, "sales rule " + id + " is worth " + rule.discountAmount()
                    + ", not the voucher's " + faceValue);
        }
        if (rule.toDate() != null && rule.toDate().isBefore(voucherLastDay)) {
            throw refused(Reason.RULE_NOT_USABLE, "sales rule " + id + " ends on " + rule.toDate()
                    + ", before the voucher's last day " + voucherLastDay);
        }
        if (voucherCurrency == null || !voucherCurrency.equalsIgnoreCase(currency)) {
            throw refused(Reason.CURRENCY_UNSUPPORTED, "the voucher is in " + voucherCurrency + "; the store sells in "
                    + currency);
        }
    }

    @Override
    public void bind(GetMoreCoupon coupon, BigDecimal faceValue, String voucherCurrency) {
        // The rule carries the value, and precheck verified it before the coupon was generated.
    }

    @Override
    public boolean partialSpending() {
        return false;
    }

    @Override
    public String name() {
        return LiveAmountModel.RULE_AMOUNT_SINGLE_USE.name();
    }

    private static GetMoreRejectedException refused(Reason reason, String message) {
        return new GetMoreRejectedException(Operation.GENERATE, reason, message);
    }
}
