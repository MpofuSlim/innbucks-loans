package zw.co.innbucks.loans.core.disbursements;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Locale;

/**
 * Tells a failure that happened before a request could leave (the connection was never opened)
 * from one that may have happened after the other side received it. Only the first is safe to
 * treat as "nothing was sent": a read timeout, a reset or a 5xx can follow a request that was
 * processed. Walks the whole cause chain, since RestTemplate wraps these in ResourceAccessException.
 */
public final class ConnectPhase {

    private ConnectPhase() {
    }

    public static boolean neverConnected(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException
                    || t instanceof PortUnreachableException
                    || t instanceof HttpConnectTimeoutException
                    || t instanceof SSLHandshakeException
                    // httpclient5 (the pooled transport, config/OutboundHttp): a connect that timed out,
                    // and a wait for a free pooled connection that timed out. The second is thrown
                    // while leasing, before any connection is chosen, so nothing can have been written.
                    || t instanceof ConnectTimeoutException
                    || t instanceof ConnectionRequestTimeoutException) {
                return true;
            }
            // The JDK socket reports a connect timeout as a plain SocketTimeoutException ("Connect timed
            // out"), and httpclient5 keeps it as the cause; a READ timeout uses the same type and is NOT
            // connect-phase.
            if (t instanceof SocketTimeoutException && t.getMessage() != null
                    && t.getMessage().toLowerCase(Locale.ROOT).contains("connect timed out")) {
                return true;
            }
        }
        return false;
    }
}
