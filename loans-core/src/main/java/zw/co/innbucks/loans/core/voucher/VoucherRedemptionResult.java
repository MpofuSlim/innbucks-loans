package zw.co.innbucks.loans.core.voucher;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A redemption as the till is told it. {@code replayed} is true when the reference had already been redeemed and this
 * is that first answer again: nothing more was spent.
 */
public record VoucherRedemptionResult(String reference, String maskedCode, BigDecimal amount,
                                      BigDecimal balanceAfter, String currency, VoucherStatus status,
                                      String outletId, LocalDateTime redeemedAt, boolean replayed) {

    static VoucherRedemptionResult of(VoucherRedemption redemption, Voucher voucher, boolean replayed) {
        VoucherStatus status = redemption.getBalanceAfter().signum() == 0 ? VoucherStatus.REDEEMED
                : VoucherStatus.PARTIALLY_REDEEMED;
        return new VoucherRedemptionResult(redemption.getMerchantReference(), voucher.maskedCode(),
                redemption.getAmount(), redemption.getBalanceAfter(), voucher.getCurrency(), status,
                redemption.getOutletId(), redemption.getRedeemedAt(), replayed);
    }
}
