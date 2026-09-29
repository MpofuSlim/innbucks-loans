package zw.co.reikan.loans.core.ndasenda;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A lodgement that did not come back accepted, classified by the one question that matters for an
 * irreversible payroll stop order: could it have reached Ndasenda?
 *
 * <p>Only a failure proven to have happened before the request left (or one Ndasenda answered by
 * saying it processed nothing) may be sent again. Everything else may have lodged the deduction,
 * so it is held for Ndasenda's own answer: never re-sent, never marked failed.</p>
 *
 * <p>The message is safe to store on the loan and in the audit trail: statuses and exception
 * types only, never an upstream body (which may echo the national ID back).</p>
 */
public class LodgementException extends RuntimeException {

    public enum Kind {
        /** Never sent, for a reason of this loan's own (the deduction could not be built). May be sent again. */
        NOT_SENT,
        /**
         * Nothing was lodged because Ndasenda could not be reached or processed nothing: a connect-phase
         * failure, the access token could not be fetched, a 401 after the one token refresh, or a 429.
         * May be sent again later; there is no point trying another loan now.
         */
        NDASENDA_UNAVAILABLE,
        /** Ndasenda's own definite refusal (a 4xx that is not about our credentials or its rate limit). Nothing was lodged. */
        REFUSED,
        /** The request may have reached Ndasenda and the deduction may be live. Held, never re-sent. */
        OUTCOME_UNKNOWN
    }

    private final Kind kind;

    public LodgementException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    /** Whether this lodgement may be sent again: only when nothing can have been lodged. */
    public boolean mayRetry() {
        return kind == Kind.NOT_SENT || kind == Kind.NDASENDA_UNAVAILABLE;
    }

    /**
     * Classifies a failure of the lodgement POST. Only Ndasenda's own 4xx proves nothing was lodged;
     * of those, 408 (it stopped reading, which says nothing about what it did) and 409 (plausibly
     * "this reference already exists", i.e. an earlier lodgement landed) stay unknown, and 401/429 are
     * answers about our credentials or its rate limit rather than about the loan. A 5xx, a read
     * timeout, a reset, a 2xx/3xx whose body cannot be read: all may follow a lodgement that landed.
     */
    static LodgementException classify(RuntimeException ex) {
        if (ex instanceof LodgementException lodgement) {
            return lodgement;
        }
        if (ex instanceof HttpClientErrorException http) {
            int status = http.getStatusCode().value();
            // The upstream body is deliberately not kept: it is already logged by the HTTP client, and
            // may echo the national ID back.
            if (status == 401 || status == 429) {
                return new LodgementException(Kind.NDASENDA_UNAVAILABLE,
                        "Ndasenda answered HTTP " + status + " and processed nothing", null);
            }
            if (status == 408 || status == 409) {
                return new LodgementException(Kind.OUTCOME_UNKNOWN,
                        "Ndasenda answered HTTP " + status + ", which does not say whether it lodged the deduction", null);
            }
            return new LodgementException(Kind.REFUSED, "Ndasenda refused the lodgement with HTTP " + status, null);
        }
        if (ex instanceof RestClientResponseException http) {
            return new LodgementException(Kind.OUTCOME_UNKNOWN,
                    "Ndasenda answered HTTP " + http.getStatusCode().value(), null);
        }
        if (ex instanceof UnknownContentTypeException unreadable) {
            return new LodgementException(Kind.OUTCOME_UNKNOWN, "Ndasenda answered HTTP "
                    + unreadable.getStatusCode().value() + " with an unreadable body (" + unreadable.getContentType() + ")", ex);
        }
        if (neverLeft(ex)) {
            return new LodgementException(Kind.NDASENDA_UNAVAILABLE, "Ndasenda could not be reached: " + describe(ex), ex);
        }
        return new LodgementException(Kind.OUTCOME_UNKNOWN, describe(ex), ex);
    }

    /**
     * True only for a failure to CONNECT: the request cannot have left. A read timeout or a reset is
     * not one of these — it can follow a request Ndasenda received and acted on.
     */
    static boolean neverLeft(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = ex; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException || isConnectTimeout(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnectTimeout(Throwable t) {
        String type = t.getClass().getSimpleName();
        // java.net.http.HttpConnectTimeoutException; Apache HttpClient's and Netty's ConnectTimeoutException.
        if ("HttpConnectTimeoutException".equals(type) || "ConnectTimeoutException".equals(type)) {
            return true;
        }
        // HttpURLConnection (behind the SimpleClientHttpRequestFactory this service uses) reports a
        // connect timeout as a bare SocketTimeoutException; only its wording tells it apart from a read
        // timeout, which can follow a delivered request. Anything else stays unknown.
        return t instanceof SocketTimeoutException && "connect timed out".equalsIgnoreCase(t.getMessage());
    }

    /**
     * Exception types, plus the message of a {@code java.net} root cause ("Read timed out", "Connection
     * refused"), which never carries a request or response body. No other message is trusted to be
     * clean: a parser's can quote the body, and the body may echo the national ID.
     */
    public static String describe(Throwable ex) {
        Throwable root = ex;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (root.getCause() != null && seen.add(root)) {
            root = root.getCause();
        }
        String described = ex.getClass().getSimpleName();
        if (root != ex) {
            described += " caused by " + root.getClass().getSimpleName();
        }
        if (root.getClass().getName().startsWith("java.net.") && root.getMessage() != null) {
            described += ": " + root.getMessage();
        }
        return described;
    }
}
