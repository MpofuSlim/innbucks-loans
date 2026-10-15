package zw.co.innbucks.loans.core.getmore;

/**
 * Everything a {@link GetMoreStore} call can fail with, in three kinds that say what the caller may assume:
 * {@link GetMoreRejectedException} (the store said no; nothing was applied), {@link GetMoreUnavailableException} (the
 * request was not processed, or a read failed) and {@link GetMoreOutcomeUnknownException} (a write may have applied).
 *
 * <p><b>The message never contains a coupon code</b>: at most its length and its last four characters
 * ({@link GetMoreCodes#masked}). Store text is masked with {@link GetMoreCodes#maskAll} before it gets here, and no
 * transport exception is chained as the cause (its message carries the request URI), so logging one of these with its
 * stack trace prints no code either.
 */
public abstract sealed class GetMoreException extends RuntimeException
        permits GetMoreRejectedException, GetMoreUnavailableException, GetMoreOutcomeUnknownException {

    /** The eight Magento calls a {@link GetMoreStore} makes. */
    public enum Operation {
        GENERATE(true),
        SEARCH_COUPONS(false),
        GET_COUPON(false),
        UPDATE_COUPON(true),
        DELETE_COUPONS(true),
        SALES_RULE(false),
        ORDERS(false),
        CREDIT_MEMOS(false);

        private final boolean write;

        Operation(boolean write) {
            this.write = write;
        }

        /** Generate, PUT and deleteByCodes change the store; a failure after the send may have applied. */
        public boolean isWrite() {
            return write;
        }
    }

    private final Operation operation;

    protected GetMoreException(Operation operation, String message) {
        super(message, null, false, true);
        this.operation = operation;
    }

    /** The call that failed; null when loans refused before any call (a deadline, an amount binding). */
    public Operation operation() {
        return operation;
    }

    static String label(Operation operation) {
        return operation == null ? "GetMore" : "GetMore " + operation;
    }
}
