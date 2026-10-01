package zw.co.innbucks.loans.core.voucher;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashSet;
import java.util.List;

/**
 * The voucher rules that are business decisions rather than code (FR-SGL-033, 034, 039; BRD 7.2 "voucher format,
 * validity period and redemption rules"), and the two keys that protect the codes.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "loans.vouchers")
public class VoucherProperties {

    /**
     * Digits in a new code, the last a check digit, in groups of four. Codes already issued keep their length, so this
     * can change without invalidating any voucher.
     */
    @Min(value = VoucherCodes.MIN_LENGTH, message = "loans.vouchers.code-length must be at least 12")
    @Max(value = VoucherCodes.MAX_LENGTH, message = "loans.vouchers.code-length must be at most 24")
    private int codeLength = 16;

    /**
     * Market days a voucher stays redeemable after the day it is issued; it lapses at the end of the last one. The
     * period is OQ-08, still to be agreed with GetMore.
     */
    @Min(value = 1, message = "loans.vouchers.validity-days must be at least 1")
    private int validityDays = 30;

    /** Whether a voucher may be spent over several purchases (OQ-08); when not, a redemption must take it all. */
    private boolean partialRedemptionAllowed = true;

    /** The channels a voucher is sent on, tried in this order until one accepts it (FR-SGL-034). */
    @NotEmpty(message = "loans.vouchers.delivery-channels must name at least one channel")
    private List<VoucherChannel> deliveryChannels = List.of(VoucherChannel.SMS, VoucherChannel.WHATSAPP);

    /**
     * Keys a code to the row it belongs to (HMAC-SHA256), so a code typed at a till finds its voucher without the code
     * being stored. At least 32 bytes. Blank: no voucher can be issued, looked up or redeemed. Changing it orphans every
     * voucher already issued, so it is never rotated while vouchers are open.
     */
    @ToString.Exclude
    private String codeHmacKey = "";

    /**
     * Encrypts the code at rest (AES-256-GCM), so it can be sent again and shown to staff entitled to see it. Base64 of
     * exactly 32 bytes. Blank: as for {@link #codeHmacKey}.
     */
    @ToString.Exclude
    private String codeEncryptionKey = "";

    @AssertTrue(message = "loans.vouchers.code-length must be a multiple of 4: codes are written in groups of four")
    public boolean isCodeLengthGrouped() {
        return codeLength % VoucherCodes.GROUP == 0;
    }

    @AssertTrue(message = "loans.vouchers.delivery-channels names a channel twice")
    public boolean isDeliveryChannelsDistinct() {
        return deliveryChannels == null || new HashSet<>(deliveryChannels).size() == deliveryChannels.size();
    }
}
