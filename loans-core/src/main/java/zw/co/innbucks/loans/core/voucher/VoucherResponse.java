package zw.co.innbucks.loans.core.voucher;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A voucher as staff see it (FR-SGL-035): its code masked (FR-SGL-040), its status as of now (an open voucher past its
 * expiry reads EXPIRED even before the expiry job marks it), and where sending it stands. {@code codeSource} says whose
 * code it is; the GetMore fields after it are absent for loans' own vouchers. For a GetMore voucher
 * {@code redeemedAmount} is spent net of refunds, and {@code heldAmount} is ordered at GetMore but not yet invoiced.
 */
public record VoucherResponse(Long id, VoucherProduct product, String merchantCode, String merchantName,
                              String loanAccount, String disbursementReference,
                              String customerReference, String customerName, String customerMsisdn,
                              String maskedCode, BigDecimal faceValue, BigDecimal redeemedAmount, BigDecimal balance,
                              String currency, LocalDateTime issuedAt, LocalDateTime expiresAt, VoucherStatus status,
                              LocalDateTime lastRedeemedAt, String lastRedeemedOutlet, String cancelledBy,
                              LocalDateTime cancelledAt, String cancellationReason,
                              VoucherDeliveryStatus deliveryStatus, VoucherChannel deliveredChannel,
                              LocalDateTime deliveryUpdatedAt, VoucherCodeSource codeSource,
                              @JsonInclude(JsonInclude.Include.NON_NULL) GetMoreMode storeMode,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Long externalCouponId,
                              @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal refundedAmount,
                              @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal heldAmount,
                              @JsonInclude(JsonInclude.Include.NON_NULL) VoucherStopReason stoppedReason,
                              @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime syncedAt,
                              @JsonInclude(JsonInclude.Include.NON_NULL) VoucherSyncIssueKind syncBlockedBy,
                              @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime couponWithdrawnAt) {

    public static VoucherResponse of(Voucher voucher, LocalDateTime now) {
        boolean getMore = voucher.isGetMore();
        return new VoucherResponse(voucher.getId(), voucher.getProduct(), voucher.getMerchant().getMerchantCode(),
                voucher.getMerchant().getCompanyName(), voucher.getLoanAccount(),
                voucher.getDisbursementReference(), voucher.getCustomerReference(), voucher.getCustomerName(),
                MsisdnUtils.mask(voucher.getCustomerMsisdn()), voucher.maskedCode(), voucher.getFaceValue(),
                voucher.getRedeemedAmount(), voucher.balance(), voucher.getCurrency(), voucher.getIssuedAt(),
                voucher.getExpiresAt(), voucher.statusAt(now), voucher.getLastRedeemedAt(),
                voucher.getLastRedeemedOutlet(), voucher.getCancelledBy(), voucher.getCancelledAt(),
                voucher.getCancellationReason(), voucher.getDeliveryStatus(), voucher.getDeliveredChannel(),
                voucher.getDeliveryUpdatedAt(), voucher.getCodeSource(),
                getMore ? voucher.getStoreMode() : null,
                getMore ? voucher.getExternalCouponId() : null,
                getMore ? voucher.getRefundedAmount() : null,
                getMore ? voucher.getHeldAmount() : null,
                getMore ? voucher.getStoppedReason() : null,
                getMore ? voucher.getSyncedAt() : null,
                getMore ? voucher.getSyncBlockedBy() : null,
                getMore ? voucher.getCouponWithdrawnAt() : null);
    }
}
