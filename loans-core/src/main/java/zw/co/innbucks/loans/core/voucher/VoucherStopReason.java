package zw.co.innbucks.loans.core.voucher;

/**
 * Why a GetMore voucher was stopped before its own expiry. A voucher that lapsed on its own has none. On payday a bank
 * or store stop is provisional under DEBT_STANDS: the borrower must not be collected the full amount, as final, for a
 * voucher the bank or the store took away.
 */
public enum VoucherStopReason {
    /** The bank withdrew the coupon at GetMore (a loan being cancelled, a written-off loan). */
    BANK_STOPPED,
    /** GetMore deleted the coupon on its own: a GET of it now answers "No such entity". */
    STORE_WITHDRAWN,
    /** A single-use coupon (LIVE, one purchase per coupon) was used once for less than its value: the rest is lost. */
    SINGLE_USE_EXHAUSTED,
    /** Staging only: expired on request through the simulator. */
    SIMULATOR_EXPIRED
}
