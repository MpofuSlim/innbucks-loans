package zw.co.innbucks.loans.core.getmore.live;

import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code GETMORE_LIVE_AMOUNT_MODEL=NONE}, the default: a Magento coupon carries no amount and GetMore has not said how
 * a per-loan amount is held, so LIVE refuses before any generate. Fails closed: no coupon is minted that the store
 * would value at whatever its rule happens to say.
 */
public final class NoLiveAmountModel implements GetMoreAmountBinding {

    static final String UNSET = "GETMORE_LIVE_AMOUNT_MODEL is NONE: GetMore has not said how a per-loan amount is held";

    @Override
    public void precheck(GetMoreSalesRule rule, BigDecimal faceValue, String currency, LocalDate voucherLastDay) {
        throw new GetMoreRejectedException(Operation.GENERATE, GetMoreRejectedException.Reason.AMOUNT_MODEL_UNSET,
                UNSET);
    }

    @Override
    public void bind(GetMoreCoupon coupon, BigDecimal faceValue, String currency) {
        throw new GetMoreRejectedException(Operation.GENERATE, GetMoreRejectedException.Reason.AMOUNT_MODEL_UNSET,
                UNSET);
    }

    @Override
    public boolean partialSpending() {
        return false;
    }

    @Override
    public String name() {
        return LiveAmountModel.NONE.name();
    }
}
