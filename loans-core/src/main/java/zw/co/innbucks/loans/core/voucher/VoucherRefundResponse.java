package zw.co.innbucks.loans.core.voucher;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A refund of a GetMore sale, as staff see it: the credit memo it came from, the order it was against, what it gave
 * back and what was left after. {@code refundedAt} is GetMore's own time, {@code recordedAt} when loans read it.
 */
public record VoucherRefundResponse(Long id, String merchantReference, String creditMemoNumber, Long externalOrderId,
                                    BigDecimal amount, BigDecimal balanceAfter, LocalDateTime refundedAt,
                                    LocalDateTime recordedAt, String recordedBy) {

    public static VoucherRefundResponse of(VoucherRefund refund) {
        return new VoucherRefundResponse(refund.getId(), refund.getMerchantReference(),
                refund.getExternalCreditmemoNumber(), refund.getExternalOrderId(), refund.getAmount(),
                refund.getBalanceAfter(), refund.getRefundedAt(), refund.getRecordedAt(), refund.getRecordedBy());
    }
}
