package zw.co.innbucks.loans.core.getmore;

/**
 * The request was not processed (it never left, was refused at the door, or was throttled), or a read failed. A write
 * that fails this way applied nothing; a read can simply be asked again later. Never retried here.
 */
public final class GetMoreUnavailableException extends GetMoreException {

    public enum Reason {
        /** {@code GETMORE_MODE=OFF}. */
        OFF,
        /** LIVE with a blank base URL or OAuth key: refused before any network call. */
        UNCONFIGURED,
        /** The connection was never opened (refused, connect timeout, no pooled connection in time). */
        NOT_REACHED,
        /** 401: the signature, nonce, token or the integration's ACL. */
        AUTH,
        /** 429. */
        THROTTLED,
        /** A redirect, an unexpected 4xx, or a body that is not Magento's (a WAF or proxy page). */
        BLOCKED,
        /** Magento's masked internal error, or a 5xx, on a read. */
        UPSTREAM_ERROR,
        /** A 2xx whose body is not the shape asked for, on a read. */
        BAD_RESPONSE,
        /** A read timed out or was reset after it was sent. */
        READ_FAILED,
        /** The caller's budget ran out before the next call. */
        DEADLINE,
        /** Another sync of this voucher is running. */
        BUSY
    }

    private final Reason reason;
    private final int httpStatus;

    public GetMoreUnavailableException(Operation operation, Reason reason) {
        this(operation, reason, 0, null);
    }

    public GetMoreUnavailableException(Operation operation, Reason reason, String detail) {
        this(operation, reason, 0, detail);
    }

    /**
     * @param httpStatus the store's status, 0 when there was no response
     * @param detail     masked text for the operator, or null
     */
    public GetMoreUnavailableException(Operation operation, Reason reason, int httpStatus, String detail) {
        super(operation, label(operation) + " unavailable (" + reason
                + (httpStatus > 0 ? ", HTTP " + httpStatus : "") + ")"
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public Reason reason() {
        return reason;
    }

    /** The store's status, or 0 when there was no response. */
    public int httpStatus() {
        return httpStatus;
    }
}
