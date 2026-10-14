package zw.co.innbucks.loans.core.voucher;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One purchase made with a voucher, as staff see it. {@code source} says how it reached loans; a GetMore sale read back
 * by a sync also carries GetMore's order number and when loans recorded it ({@code redeemedAt} is GetMore's own time).
 */
public record VoucherRedemptionResponse(Long id, String merchantReference, BigDecimal amount, BigDecimal balanceAfter,
                                        String outletId, String outletName, String redeemedBy,
                                        LocalDateTime redeemedAt, VoucherRedemptionSource source,
                                        @JsonInclude(JsonInclude.Include.NON_NULL) String externalOrderNumber,
                                        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime recordedAt) {

    public static VoucherRedemptionResponse of(VoucherRedemption redemption) {
        return new VoucherRedemptionResponse(redemption.getId(), redemption.getMerchantReference(),
                redemption.getAmount(), redemption.getBalanceAfter(), redemption.getOutletId(),
                redemption.getOutletName(), redemption.getRedeemedBy(), redemption.getRedeemedAt(),
                redemption.getSource(), redemption.getExternalOrderNumber(), redemption.getRecordedAt());
    }
}
