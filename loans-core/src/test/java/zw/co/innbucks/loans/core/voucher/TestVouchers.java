package zw.co.innbucks.loans.core.voucher;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Vouchers as the service stores them, with real fingerprints and sealed codes. */
final class TestVouchers {

    static final String CODE = "4829150673318406";
    /** Thursday 1 October 2026, 09:14:22 in Harare. */
    static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 1, 7, 14, 22);
    /** The end of 31 October 2026 in Harare: the 30th market day after issue. */
    static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 10, 31, 21, 59, 59, 999_999_000);

    private TestVouchers() {
    }

    static Voucher.VoucherBuilder issued(VoucherCodeVault vault) {
        return Voucher.builder()
                .id(7L)
                .product(VoucherProduct.STAFF_GROCERY_LOAN)
                .disbursementReference("BRNET-20261001-0007")
                .loanAccount("SGL-2026-000143")
                .staffMemberId(12L)
                .customerReference("E1012")
                .customerName("Chipo Banda")
                .customerMsisdn("+263772123123")
                .codeHmac(vault.fingerprint(CODE))
                .codeCiphertext(vault.encrypt(CODE))
                .codeLast4("8406")
                .codeLength(16)
                .faceValue(new BigDecimal("300.00"))
                .redeemedAmount(new BigDecimal("0.00"))
                .currency("USD")
                .issuedAt(ISSUED_AT)
                .expiresAt(EXPIRES_AT)
                .status(VoucherStatus.ISSUED)
                .deliveryStatus(VoucherDeliveryStatus.SENT)
                .deliveredChannel(VoucherChannel.SMS)
                .deliveryUpdatedAt(ISSUED_AT.plusSeconds(1))
                .version(0L);
    }
}
