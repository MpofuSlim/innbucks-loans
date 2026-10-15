package zw.co.innbucks.loans.core.getmore.simulator;

/**
 * Why the simulated store's test till (or a simulator action) refused, each a stable code the staging test actions
 * answer with. None of these is a Magento answer: they are the simulated store's checkout saying no.
 */
public enum SimulatorRefusal {
    /** No live coupon has the code (or it has no allowance, or its uses are used up): Magento's "is not valid". */
    SIM_COUPON_NOT_FOUND(404, "The coupon code is not valid."),
    /** The rule is inactive or past its last day, or the coupon's expiration date has passed (assumption A4). */
    SIM_COUPON_EXPIRED(409, "The coupon code has expired."),
    /** More than is left on the coupon. */
    SIM_INSUFFICIENT_BALANCE(409, "The amount is more than is left on this coupon"),
    /** Spending in parts is off (assumption A2 switched off) and the amount is not exactly what is left. */
    SIM_PARTIAL_SPEND_NOT_ALLOWED(409, "This coupon must be spent in one purchase"),
    /** The sale's currency is not the simulated store's. */
    SIM_CURRENCY_MISMATCH(409, "The simulated store sells in another currency"),
    /** The reference was already used for a different sale or refund. */
    SIM_REFERENCE_REUSED(409, "That reference was already used for a different sale"),
    /** No simulated sale has that order number. */
    SIM_SALE_NOT_FOUND(404, "No simulated sale has that order number"),
    /** The sale is invoiced in full already. */
    SIM_NOTHING_TO_INVOICE(409, "Nothing is left to invoice on this sale"),
    /** Nothing invoiced on the sale is left to refund. */
    SIM_NOTHING_TO_REFUND(409, "Nothing invoiced on this sale is left to refund"),
    /** More than is left to refund on the sale. */
    SIM_REFUND_TOO_LARGE(409, "The amount is more than is left to refund on this sale"),
    /** The voucher is not a coupon of the simulated store. */
    NOT_A_SIMULATED_VOUCHER(409, "This voucher is not a simulated GetMore voucher");

    private final int httpStatus;
    private final String message;

    SimulatorRefusal(int httpStatus, String message) {
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
