package zw.co.innbucks.loans.core.voucher;

/** What started a GetMore sync of a voucher. No job ever does: each is a person's or a request's. */
public enum GetMoreSyncTrigger {
    /** The borrower opened their loan in the SuperApp (asynchronous). */
    BORROWER_READ,
    /** Staging's simulator spent, invoiced, refunded or expired a coupon (synchronous, after its own commit). */
    SIMULATOR,
    /** Staff asked for one ({@code POST /vouchers/{id}/getmore-syncs}). */
    OPERATOR,
    /** Cancelling the loan reads GetMore before and after withdrawing the coupon. */
    CANCELLATION,
    /** The payday sync before the collection statement. */
    PAYDAY
}
