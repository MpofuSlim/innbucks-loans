package zw.co.innbucks.loans.core.voucher;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One purchase made with a voucher, as staff see it. */
public record VoucherRedemptionResponse(Long id, String merchantReference, BigDecimal amount, BigDecimal balanceAfter,
                                        String outletId, String outletName, String redeemedBy,
                                        LocalDateTime redeemedAt) {

    public static VoucherRedemptionResponse of(VoucherRedemption redemption) {
        return new VoucherRedemptionResponse(redemption.getId(), redemption.getMerchantReference(),
                redemption.getAmount(), redemption.getBalanceAfter(), redemption.getOutletId(),
                redemption.getOutletName(), redemption.getRedeemedBy(), redemption.getRedeemedAt());
    }
}
