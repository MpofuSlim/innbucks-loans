package zw.co.innbucks.loans.core.voucher;

/** How a spend reached loans. */
public enum VoucherRedemptionSource {
    /** A merchant's till called loans' redemption endpoint. */
    TILL,
    /** Read back from a GetMore order by a sync: one row per order per invoiced level. */
    GETMORE_SYNC
}
