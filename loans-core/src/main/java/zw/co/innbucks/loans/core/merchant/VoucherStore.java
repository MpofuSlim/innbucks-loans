package zw.co.innbucks.loans.core.merchant;

/**
 * Whose code a merchant's vouchers carry. A record of the merchant, set only by a reviewed migration and never a
 * setting: V37 makes {@code getmore-groceries} a GETMORE store.
 */
public enum VoucherStore {
    /** Loans' own digit codes, spent at the merchant's tills through the voucher redemption endpoints. */
    LOANS,
    /**
     * GetMore Groceries' coupons: issued by GetMore's own store when a Staff Grocery Loan is accepted, spent there, and
     * read back from its orders and credit memos (GETMORE_MODE LIVE or SIMULATED). Loans' tills refuse their codes.
     */
    GETMORE
}
