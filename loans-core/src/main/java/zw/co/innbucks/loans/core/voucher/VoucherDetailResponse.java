package zw.co.innbucks.loans.core.voucher;

import java.util.List;

/** A voucher with every purchase made with it and every attempt to send it, oldest first. */
public record VoucherDetailResponse(VoucherResponse voucher, List<VoucherRedemptionResponse> redemptions,
                                    List<VoucherDeliveryResponse> deliveries) {
}
