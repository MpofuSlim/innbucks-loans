package zw.co.innbucks.loans.core.getmore.simulator;

import org.springframework.beans.factory.ObjectProvider;
import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreException;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException.Reason;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * SIMULATED's amount model ({@value #NAME}), assumption A1: each simulated coupon carries its own allowance, bound
 * once, after the coupon is found, to the voucher's face value; equal again is nothing, another value is refused.
 * {@link #precheck} asks only what the simulated store can honour: an active, auto-generating rule, a value in whole
 * cents up to {@link SimulatedTill#MAX_AMOUNT} (what its till sells in and its columns hold), and the voucher in the
 * simulated store's currency (its till refuses any other, so a coupon in another would never be spendable).
 * Spending in parts follows {@code GETMORE_SIMULATOR_PARTIAL_SPENDING} (assumption A2). Plain class, built only by the
 * configuration's mode switch.
 */
public final class SimulatedAmountBinding implements GetMoreAmountBinding {

    public static final String NAME = "SIMULATED_PER_COUPON";

    private final ObjectProvider<SimulatedStoreBackend> backend;
    private final GetMoreProperties.Simulator simulator;

    public SimulatedAmountBinding(ObjectProvider<SimulatedStoreBackend> backend,
                                  GetMoreProperties.Simulator simulator) {
        this.backend = backend;
        this.simulator = simulator;
    }

    @Override
    public void precheck(GetMoreSalesRule rule, BigDecimal faceValue, String currency, LocalDate voucherLastDay) {
        if (!rule.active()) {
            throw refused(Reason.RULE_NOT_USABLE, "simulated sales rule " + rule.ruleId() + " is not active");
        }
        if (!rule.useAutoGeneration() && !"AUTO".equals(rule.couponType())) {
            throw refused(Reason.RULE_NOT_USABLE, "simulated sales rule " + rule.ruleId()
                    + " does not generate coupons");
        }
        if (faceValue == null || faceValue.signum() <= 0) {
            throw refused(Reason.VALUE_UNSUPPORTED, "a simulated coupon is worth more than 0, not " + faceValue);
        }
        if (faceValue.stripTrailingZeros().scale() > 2 || faceValue.compareTo(SimulatedTill.MAX_AMOUNT) > 0) {
            // The till sells in cents, and the allowance column holds NUMERIC(12,4): anything else is refused here,
            // before a coupon is minted, never failed at the bind after it.
            throw refused(Reason.VALUE_UNSUPPORTED, "a simulated coupon is worth whole cents up to "
                    + SimulatedTill.MAX_AMOUNT.toPlainString() + ", not " + faceValue.toPlainString());
        }
        if (currency == null || !currency.equalsIgnoreCase(simulator.getCurrency())) {
            throw refused(Reason.CURRENCY_UNSUPPORTED, "the voucher is in " + currency
                    + "; the simulated store sells in " + simulator.getCurrency());
        }
    }

    /**
     * Binds the allowance through the backend directly (no store call behind it, unlike live), so its failures are
     * classified here exactly as the store's facade classifies its own ({@link SimulatedGetMoreStore#classify}): the
     * code above the port then runs the same path as live, whatever the simulator's database did.
     */
    @Override
    public void bind(GetMoreCoupon coupon, BigDecimal faceValue, String currency) {
        try {
            backend.getObject().bindAllowance(coupon.couponId(), faceValue, currency);
        } catch (GetMoreException | IllegalArgumentException alreadyClassified) {
            throw alreadyClassified;
        } catch (RuntimeException failure) {
            throw SimulatedGetMoreStore.classify(Operation.UPDATE_COUPON, failure);
        }
    }

    @Override
    public boolean partialSpending() {
        return simulator.isPartialSpending();
    }

    @Override
    public String name() {
        return NAME;
    }

    private static GetMoreRejectedException refused(Reason reason, String message) {
        return new GetMoreRejectedException(Operation.GENERATE, reason, message);
    }
}
