package zw.co.innbucks.loans.core.voucher;

/** Who made a voucher's code. */
public enum VoucherCodeSource {
    /** Loans: 16 digits with a check digit, spent at the merchant's tills through loans' redemption endpoints. */
    LOANS,
    /**
     * GetMore's store: a coupon code such as {@code INN-K7Q2M3XW-7KQ2M9XWD4RT8B1C}, shown exactly as GetMore made it,
     * spent at GetMore, and recorded here only from GetMore's orders and credit memos. Loans' tills refuse it.
     */
    GETMORE
}
