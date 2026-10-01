package zw.co.innbucks.loans.core.voucher;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What a till is told about a voucher (FR-SGL-036): whether it can be spent now, how much is left, until when, and the
 * holder's name as far as a receipt needs it ({@code Tendai M.}). Nothing else about the customer reaches GetMore.
 */
public record VoucherValidationResponse(String maskedCode, VoucherStatus status, boolean redeemable,
                                        BigDecimal faceValue, BigDecimal balance, String currency,
                                        LocalDateTime expiresAt, String holderName,
                                        boolean partialRedemptionAllowed) {

    static VoucherValidationResponse of(Voucher voucher, LocalDateTime now, boolean partialRedemptionAllowed) {
        VoucherStatus status = voucher.statusAt(now);
        return new VoucherValidationResponse(voucher.maskedCode(), status, status.isOpen(), voucher.getFaceValue(),
                voucher.balance(), voucher.getCurrency(), voucher.getExpiresAt(), holderName(voucher.getCustomerName()),
                partialRedemptionAllowed);
    }

    /** The first name and the initial of the last: {@code Tendai Moyo} is {@code Tendai M.}. */
    static String holderName(String fullName) {
        String[] parts = fullName.strip().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }
}
