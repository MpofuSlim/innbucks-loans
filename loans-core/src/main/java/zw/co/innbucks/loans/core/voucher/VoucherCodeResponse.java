package zw.co.innbucks.loans.core.voucher;

/**
 * A voucher's code in full, for someone entitled to see it (FR-SGL-040): {@code code} to read out or show,
 * {@code scanValue} (digits only) for a QR code or a till.
 */
public record VoucherCodeResponse(Long voucherId, String code, String scanValue) {

    static VoucherCodeResponse of(Long voucherId, String code) {
        return new VoucherCodeResponse(voucherId, VoucherCodes.display(code), VoucherCodes.scanValue(code));
    }

    @Override
    public String toString() {
        return "VoucherCodeResponse[voucherId=" + voucherId + "]";
    }
}
