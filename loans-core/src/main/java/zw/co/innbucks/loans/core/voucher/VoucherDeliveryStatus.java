package zw.co.innbucks.loans.core.voucher;

/** Where sending the voucher to the customer stands. */
public enum VoucherDeliveryStatus {
    /** Waiting to be sent. */
    PENDING,
    /** Being sent; never sent twice from here. */
    SENDING,
    /** A channel accepted it. */
    SENT,
    /** Every channel refused it: an exception for follow-up (FR-SGL-037). */
    FAILED
}
