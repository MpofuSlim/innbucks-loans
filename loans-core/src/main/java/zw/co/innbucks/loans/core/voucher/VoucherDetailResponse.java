package zw.co.innbucks.loans.core.voucher;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A voucher with every purchase made with it and every attempt to send it, oldest first. A GetMore voucher also has
 * the refunds read back from GetMore and its open sync issues; both are absent for loans' own vouchers.
 */
public record VoucherDetailResponse(VoucherResponse voucher, List<VoucherRedemptionResponse> redemptions,
                                    List<VoucherDeliveryResponse> deliveries,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) List<VoucherRefundResponse> refunds,
                                    @JsonInclude(JsonInclude.Include.NON_NULL)
                                    List<VoucherSyncIssueResponse> syncIssues) {
}
