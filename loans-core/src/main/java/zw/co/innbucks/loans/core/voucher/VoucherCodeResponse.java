package zw.co.innbucks.loans.core.voucher;

/**
 * A voucher's code in full, for someone entitled to see it (FR-SGL-040): {@code code} to read out or show,
 * {@code scanValue} for a QR code or a till. Loans' own code is shown in groups of four with its digits alone to scan;
 * a GetMore code is shown exactly as GetMore made it, in both, never regrouped (its dashes are part of it).
 */
public record VoucherCodeResponse(Long voucherId, String code, String scanValue) {

    static VoucherCodeResponse of(Long voucherId, String code, VoucherCodeSource source) {
        if (source == VoucherCodeSource.GETMORE) {
            return new VoucherCodeResponse(voucherId, code, code);
        }
        return new VoucherCodeResponse(voucherId, VoucherCodes.display(code), VoucherCodes.scanValue(code));
    }

    @Override
    public String toString() {
        return "VoucherCodeResponse[voucherId=" + voucherId + "]";
    }
}
