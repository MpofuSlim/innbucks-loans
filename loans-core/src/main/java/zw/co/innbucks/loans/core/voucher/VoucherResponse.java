package zw.co.innbucks.loans.core.voucher;

import zw.co.innbucks.loans.core.MsisdnUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A voucher as staff see it (FR-SGL-035): its code masked (FR-SGL-040), its status as of now (an open voucher past its
 * expiry reads EXPIRED even before the expiry job marks it), and where sending it stands.
 */
public record VoucherResponse(Long id, VoucherProduct product, String loanAccount, String disbursementReference,
                              String customerReference, String customerName, String customerMsisdn,
                              String maskedCode, BigDecimal faceValue, BigDecimal redeemedAmount, BigDecimal balance,
                              String currency, LocalDateTime issuedAt, LocalDateTime expiresAt, VoucherStatus status,
                              LocalDateTime lastRedeemedAt, String lastRedeemedOutlet, String cancelledBy,
                              LocalDateTime cancelledAt, String cancellationReason,
                              VoucherDeliveryStatus deliveryStatus, VoucherChannel deliveredChannel,
                              LocalDateTime deliveryUpdatedAt) {

    public static VoucherResponse of(Voucher voucher, LocalDateTime now) {
        return new VoucherResponse(voucher.getId(), voucher.getProduct(), voucher.getLoanAccount(),
                voucher.getDisbursementReference(), voucher.getCustomerReference(), voucher.getCustomerName(),
                MsisdnUtils.mask(voucher.getCustomerMsisdn()), voucher.maskedCode(), voucher.getFaceValue(),
                voucher.getRedeemedAmount(), voucher.balance(), voucher.getCurrency(), voucher.getIssuedAt(),
                voucher.getExpiresAt(), voucher.statusAt(now), voucher.getLastRedeemedAt(),
                voucher.getLastRedeemedOutlet(), voucher.getCancelledBy(), voucher.getCancelledAt(),
                voucher.getCancellationReason(), voucher.getDeliveryStatus(), voucher.getDeliveredChannel(),
                voucher.getDeliveryUpdatedAt());
    }
}
