package zw.co.innbucks.loans.core.getmore;

/**
 * The store said no, and nothing was applied: a 400 or 404 with Magento's error envelope, or loans refusing on the
 * store's behalf before a call (an amount model that cannot make the coupon). {@link #renderedMessage()} is Magento's
 * template with its parameters substituted and every code masked.
 */
public sealed class GetMoreRejectedException extends GetMoreException permits GetMoreNotFoundException {

    public enum Reason {
        /** The store refused the request (a 400 with Magento's envelope). */
        INVALID,
        /** No such entity (a 404 with Magento's envelope, on a write). */
        NOT_FOUND,
        /** LIVE has no amount model yet: GetMore has not said how a per-loan amount is held. */
        AMOUNT_MODEL_UNSET,
        /** The sales rule cannot back a voucher (inactive, not auto-generating, wrong action or usage limit). */
        RULE_NOT_USABLE,
        /** The rule cannot carry this voucher's value. */
        VALUE_UNSUPPORTED,
        /** The voucher's currency is not the store's. */
        CURRENCY_UNSUPPORTED,
        /** A generated code did not carry the prefix (or length) asked for. */
        PREFIX_NOT_HONOURED
    }

    private final int httpStatus;
    private final Reason reason;
    private final String renderedMessage;

    /**
     * @param httpStatus the store's status, or 0 when loans refused before any call
     * @param renderedMessage already masked ({@link GetMoreCodes#maskAll})
     */
    public GetMoreRejectedException(Operation operation, int httpStatus, Reason reason, String renderedMessage) {
        super(operation, label(operation) + " rejected (" + reason
                + (httpStatus > 0 ? ", HTTP " + httpStatus : "") + "): " + renderedMessage);
        this.httpStatus = httpStatus;
        this.reason = reason;
        this.renderedMessage = renderedMessage;
    }

    /** Refused by loans before any call, on the store's behalf. */
    public GetMoreRejectedException(Operation operation, Reason reason, String message) {
        this(operation, 0, reason, message);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Reason reason() {
        return reason;
    }

    public String renderedMessage() {
        return renderedMessage;
    }
}
