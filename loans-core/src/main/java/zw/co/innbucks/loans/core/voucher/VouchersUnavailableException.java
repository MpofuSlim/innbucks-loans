package zw.co.innbucks.loans.core.voucher;

/**
 * Vouchers cannot be issued, read in full or redeemed because the keys that protect their codes are not configured
 * (VOUCHER_CODE_HMAC_KEY and VOUCHER_CODE_ENCRYPTION_KEY). A 503: nothing is wrong with the request.
 */
public class VouchersUnavailableException extends RuntimeException {

    public VouchersUnavailableException() {
        super("Vouchers are not available: the voucher code keys are not configured");
    }
}
