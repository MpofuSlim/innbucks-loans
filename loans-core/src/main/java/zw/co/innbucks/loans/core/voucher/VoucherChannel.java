package zw.co.innbucks.loans.core.voucher;

/** The channels a voucher is sent to the customer on (FR-SGL-034). */
public enum VoucherChannel {
    /** Through the InnBucks notification API. */
    SMS,
    /** Through the WhatsApp notification gateway, as text. */
    WHATSAPP
}
