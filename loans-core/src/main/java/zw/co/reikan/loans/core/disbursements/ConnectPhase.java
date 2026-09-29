package zw.co.reikan.loans.core.disbursements;

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
                    || t instanceof SSLHandshakeException) {
                return true;
            }
            // HttpURLConnection reports a connect timeout as a plain SocketTimeoutException
            // ("Connect timed out"); a READ timeout uses the same type and is NOT connect-phase.
            if (t instanceof SocketTimeoutException && t.getMessage() != null
                    && t.getMessage().toLowerCase(Locale.ROOT).contains("connect timed out")) {
                return true;
            }
        }
        return false;
    }
}
