package zw.co.innbucks.loans.core.voucher;

/**
 * What a GetMore sync could not apply, or applied with a doubt. A BLOCKING kind stops the voucher's stream of spends
 * and refunds at that point, so nothing from there on is recorded until GetMore's data changes (a later sync that no
 * longer sees it carries on); a warning is recorded and the sync goes on.
 */
public enum VoucherSyncIssueKind {
    /** A spend would take what is spent above the face value. */
    OVERSPENT(true),
    /** An order applied a rule besides the voucher's: its discount cannot be read as the voucher's alone. */
    OTHER_RULES_APPLIED(true),
    /** An order in another currency than the voucher's. */
    CURRENCY_MISMATCH(true),
    /** An amount in more than two decimal places: never rounded. */
    PRECISION(true),
    /** A discount with Magento's sign the wrong way round (they are negative). */
    SIGN(true),
    /** One order invoiced more than the voucher is worth (the 100x guard). */
    AMOUNT_EXCEEDS_FACE(true),
    /** An order now shows less invoiced than loans already recorded for it. */
    INVOICED_DECREASED(true),
    /** A credit memo gives back more than its order spent. */
    REFUND_EXCEEDS_SPEND(true),
    /** A spend, or a sale held at the till, on a cancelled voucher's code. */
    SPEND_ON_CANCELLED(true),
    /** More orders, coupons or credit memos on the prefix than one sync reads: nothing is applied. */
    TOO_MANY_ORDERS(true),
    /** A credit memo whose order GetMore no longer lists: it waits for a later sync. */
    REFUND_WITHOUT_SALE(false),
    /** A credit memo recorded as a refund is now cancelled at GetMore. */
    MEMO_REVERSED(false),
    /** A spend dated after the voucher expired: recorded, since GetMore will bill it. */
    LATE_SPEND(false),
    /** An order loans recorded a spend for is no longer listed by GetMore. */
    ORDER_VANISHED(false),
    /** Another coupon on the voucher's prefix that could not be withdrawn. */
    EXTRA_COUPON(false),
    /** A sale with another coupon on the voucher's prefix: money spent that is the loan's but not on its code. */
    SPEND_ON_OTHER_COUPON(true),
    /** GetMore deleted the coupon: the voucher is stopped (STORE_WITHDRAWN). */
    COUPON_GONE(false),
    /** A single-use coupon was used for less than its value: the voucher is stopped (SINGLE_USE_EXHAUSTED). */
    COUPON_EXHAUSTED(false);

    private final boolean blocking;

    VoucherSyncIssueKind(boolean blocking) {
        this.blocking = blocking;
    }

    /** Whether it stops the voucher's stream at that point. */
    public boolean blocking() {
        return blocking;
    }
}
