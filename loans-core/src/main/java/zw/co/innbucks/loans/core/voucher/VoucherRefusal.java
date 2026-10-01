package zw.co.innbucks.loans.core.voucher;

/** Why GetMore's till was refused a voucher (FR-SGL-036), each a stable code its integration can branch on. */
public enum VoucherRefusal {
    /** Not a voucher code at all: wrong length, a stray character, or a check digit that does not match. */
    INVALID_VOUCHER_CODE(400, "That is not a valid voucher code - check the digits"),
    /** A well-formed code no voucher has. */
    VOUCHER_NOT_FOUND(404, "No voucher has that code"),
    VOUCHER_EXPIRED(409, "This voucher has expired"),
    VOUCHER_CANCELLED(409, "This voucher has been cancelled"),
    VOUCHER_REDEEMED(409, "This voucher has already been redeemed in full"),
    /** More than is left on the voucher. */
    INSUFFICIENT_BALANCE(409, "The amount is more than is left on this voucher"),
    /** Part of the balance, where the rules say a voucher is spent in one go (FR-SGL-039). */
    PARTIAL_REDEMPTION_NOT_ALLOWED(409, "This voucher must be redeemed in full, in one purchase"),
    /** The till's currency is not the voucher's. */
    CURRENCY_MISMATCH(409, "The voucher is in a different currency"),
    /** GetMore's reference was already used for a different redemption. */
    REFERENCE_REUSED(409, "That reference was already used for a different redemption");

    private final int httpStatus;
    private final String message;

    VoucherRefusal(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
