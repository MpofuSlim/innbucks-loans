package zw.co.innbucks.loans.core.getmore.live;

import zw.co.innbucks.loans.core.disbursements.ConnectPhase;
import zw.co.innbucks.loans.core.getmore.GetMoreException;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreNotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException.Reason;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Turns what Magento (or whatever sits in front of it) answered into the port's three kinds, by the repo's
 * never-sent versus maybe-sent rule ({@link ConnectPhase}). "Write" is generate, PUT and deleteByCodes.
 *
 * <table>
 *   <caption>Classification</caption>
 *   <tr><th>Observed</th><th>Read</th><th>Write</th></tr>
 *   <tr><td>connection never opened</td><td>NOT_REACHED</td><td>NOT_REACHED</td></tr>
 *   <tr><td>401, any body (signature, nonce, token, ACL)</td><td>AUTH</td><td>AUTH</td></tr>
 *   <tr><td>429</td><td>THROTTLED</td><td>THROTTLED</td></tr>
 *   <tr><td>400 with Magento's envelope</td><td>Rejected INVALID</td><td>Rejected INVALID</td></tr>
 *   <tr><td>404 "No such entity." / "No such entity with ..." (Magento's {@code NoSuchEntityException})</td>
 *       <td>NotFound (BLOCKED on a search: a list never 404s, a route does)</td><td>Rejected NOT_FOUND</td></tr>
 *   <tr><td>400 "Internal Error... Report ID", a 5xx</td><td>UPSTREAM_ERROR (BLOCKED when the 5xx is not
 *       Magento's)</td><td>OutcomeUnknown</td></tr>
 *   <tr><td>a 3xx (never followed), another 4xx, a 4xx that is not Magento's, a 404 with any other message
 *       (Magento's "Request does not match any route.", a JSON-speaking proxy: nothing was looked up)</td>
 *       <td>BLOCKED</td><td>BLOCKED</td></tr>
 *   <tr><td>2xx of the wrong shape</td><td>BAD_RESPONSE</td><td>OutcomeUnknown</td></tr>
 *   <tr><td>read timeout, reset after the send</td><td>READ_FAILED</td><td>OutcomeUnknown</td></tr>
 * </table>
 *
 * <p>Every message is masked; no transport exception is chained (its message carries the URI).
 */
final class MagentoFailures {

    /** The searches: a 404 there is a missing route, never a missing entity. */
    private static final Set<Operation> COLLECTIONS =
            EnumSet.of(Operation.SEARCH_COUPONS, Operation.ORDERS, Operation.CREDIT_MEMOS);

    private MagentoFailures() {
    }

    /** What came back, read up to the client's limit. {@code toString} never shows the body. */
    record Raw(int status, String contentType, byte[] body, boolean truncated) {
        boolean successful() {
            return status >= 200 && status < 300;
        }

        @Override
        public String toString() {
            return "Raw[status=" + status + ", contentType=" + contentType + ", bytes="
                    + (body == null ? 0 : body.length) + (truncated ? "+" : "") + "]";
        }
    }

    /** A non-2xx answer. */
    static GetMoreException classify(Operation op, Raw raw, Collection<String> knownCodes) {
        int status = raw.status();
        Optional<MagentoErrorBody> envelope = raw.truncated() ? Optional.empty() : MagentoErrorBody.parse(raw.body());
        String rendered = rendered(envelope.orElse(null), knownCodes);
        if (status == 401) {
            return new GetMoreUnavailableException(op, Reason.AUTH, status, rendered);
        }
        if (status == 429) {
            return new GetMoreUnavailableException(op, Reason.THROTTLED, status, rendered);
        }
        if (status >= 300 && status < 400) {
            return new GetMoreUnavailableException(op, Reason.BLOCKED, status, "a redirect is never followed");
        }
        boolean masked = status == 400 && envelope.isPresent() && envelope.get().internalError();
        if (status >= 500 || masked) {
            if (op.isWrite()) {
                return new GetMoreOutcomeUnknownException(op, "HTTP " + status
                        + (rendered == null ? "" : ": " + rendered));
            }
            return new GetMoreUnavailableException(op, envelope.isPresent() ? Reason.UPSTREAM_ERROR : Reason.BLOCKED,
                    status, rendered);
        }
        if (status == 400 && envelope.isPresent()) {
            return new GetMoreRejectedException(op, status, GetMoreRejectedException.Reason.INVALID, rendered);
        }
        if (status == 404 && envelope.isPresent() && envelope.get().noSuchEntity()) {
            if (op.isWrite()) {
                return new GetMoreRejectedException(op, status, GetMoreRejectedException.Reason.NOT_FOUND, rendered);
            }
            return COLLECTIONS.contains(op)
                    ? new GetMoreUnavailableException(op, Reason.BLOCKED, status, rendered)
                    : new GetMoreNotFoundException(op, rendered);
        }
        return new GetMoreUnavailableException(op, Reason.BLOCKED, status, rendered);
    }

    /**
     * The envelope's text, or null. Never throws: classification must always end in one of the port's three kinds, so
     * a store message that cannot be rendered is left out rather than allowed to escape (a write would lose its
     * "maybe applied").
     */
    private static String rendered(MagentoErrorBody envelope, Collection<String> knownCodes) {
        if (envelope == null) {
            return null;
        }
        try {
            return envelope.render(knownCodes);
        } catch (RuntimeException unrenderable) {
            return null;
        }
    }

    /** A 2xx whose body is not the shape asked for: a read failed; a write may have applied. */
    static GetMoreException badResponse(Operation op, int status, String detail) {
        String what = "HTTP " + status + " but " + detail;
        return op.isWrite()
                ? new GetMoreOutcomeUnknownException(op, what)
                : new GetMoreUnavailableException(op, Reason.BAD_RESPONSE, status, detail);
    }

    /** The send failed. Only a connection that never opened is "not sent"; anything later may have been. */
    static GetMoreException classify(Operation op, Throwable failure) {
        String kind = innermostType(failure);
        if (ConnectPhase.neverConnected(failure)) {
            return new GetMoreUnavailableException(op, Reason.NOT_REACHED, kind);
        }
        return op.isWrite()
                ? new GetMoreOutcomeUnknownException(op, "the request may have been sent: " + kind)
                : new GetMoreUnavailableException(op, Reason.READ_FAILED, kind);
    }

    /** The innermost exception's class name: never a message, which can carry the URI. */
    static String innermostType(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName();
    }
}
