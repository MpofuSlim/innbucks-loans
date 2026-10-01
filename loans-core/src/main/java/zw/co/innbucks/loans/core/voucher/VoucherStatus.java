package zw.co.innbucks.loans.core.voucher;

/** Where a voucher stands (FR-SGL-035). */
public enum VoucherStatus {
    /** Nothing spent yet. */
    ISSUED,
    /** Some spent, some left. */
    PARTIALLY_REDEEMED,
    /** All spent. */
    REDEEMED,
    /** Lapsed with something left: the rest can no longer be spent (what becomes of the loan is OQ-09). */
    EXPIRED,
    /** Stopped before anything was spent. */
    CANCELLED;

    /** Whether something can still be spent, expiry aside. */
    public boolean isOpen() {
        return this == ISSUED || this == PARTIALLY_REDEEMED;
    }
}
