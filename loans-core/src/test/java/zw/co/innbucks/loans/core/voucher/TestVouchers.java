package zw.co.innbucks.loans.core.voucher;

import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.VoucherStore;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Vouchers as the service stores them, with real fingerprints and sealed codes. */
final class TestVouchers {

    static final String CODE = "4829150673318406";
    /** Thursday 1 October 2026, 09:14:22 in Harare. */
    static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 1, 7, 14, 22);
    /** The end of 31 October 2026 in Harare: the 30th market day after issue. */
    static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 10, 31, 21, 59, 59, 999_999_000);

    /** Rutendo Chari's simulated GetMore coupon (loan SGL-2026-000164, coupon 1207 on rule 43): the API examples'. */
    static final String GETMORE_CODE = "SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C";
    static final String GETMORE_PREFIX = "SIM-K7Q2M3XW-";
    /** Wednesday 14 October 2026, 10:20:07 in Harare. */
    static final LocalDateTime GETMORE_ISSUED_AT = LocalDateTime.of(2026, 10, 14, 8, 20, 7);
    /** The end of 13 November 2026 in Harare: the 30th market day after issue. */
    static final LocalDateTime GETMORE_EXPIRES_AT = LocalDateTime.of(2026, 11, 13, 21, 59, 59, 999_999_000);

    private TestVouchers() {
    }

    /** GetMore Groceries, the Staff Grocery Loan's merchant: a new instance each time, so no test sees another's. */
    static Merchant getMore() {
        return merchant(3L, "getmore-groceries", "GetMore Groceries");
    }

    static Merchant merchant(long id, String code, String name) {
        Merchant merchant = Merchant.builder().merchantCode(code).companyName(name)
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).build();
        merchant.setId(id);
        return merchant;
    }

    /** GetMore Groceries as V37 leaves it: a GetMore store. */
    static Merchant getMoreStore() {
        Merchant merchant = getMore();
        merchant.setVoucherStore(VoucherStore.GETMORE);
        return merchant;
    }

    /** Voucher 9: a simulated GetMore coupon, 150.00, nothing spent, never synced. */
    static Voucher.VoucherBuilder getMoreIssued(VoucherCodeVault vault) {
        return Voucher.builder()
                .id(9L)
                .product(VoucherProduct.STAFF_GROCERY_LOAN)
                .merchant(getMoreStore())
                .disbursementReference("GETMORE-SIM-1207")
                .loanAccount("SGL-2026-000164")
                .staffMemberId(9L)
                .customerReference("E1064")
                .customerName("Rutendo Chari")
                .customerMsisdn("+263771640064")
                .codeHmac(vault.fingerprint(GETMORE_CODE))
                .codeCiphertext(vault.encrypt(GETMORE_CODE))
                .codeLast4("8B1C")
                .codeLength(GETMORE_CODE.length())
                .faceValue(new BigDecimal("150.00"))
                .redeemedAmount(new BigDecimal("0.00"))
                .currency("USD")
                .issuedAt(GETMORE_ISSUED_AT)
                .expiresAt(GETMORE_EXPIRES_AT)
                .status(VoucherStatus.ISSUED)
                .deliveryStatus(VoucherDeliveryStatus.SENT)
                .deliveredChannel(VoucherChannel.WHATSAPP)
                .deliveryUpdatedAt(GETMORE_ISSUED_AT.plusSeconds(1))
                .version(0L)
                .codeSource(VoucherCodeSource.GETMORE)
                .storeMode(GetMoreMode.SIMULATED)
                .staffLoanId(164L)
                .externalCouponId(1207L)
                .externalRuleId(43L)
                .codePrefix(GETMORE_PREFIX);
    }

    static Voucher.VoucherBuilder issued(VoucherCodeVault vault) {
        return Voucher.builder()
                .id(7L)
                .product(VoucherProduct.STAFF_GROCERY_LOAN)
                .merchant(getMore())
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
