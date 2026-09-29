package zw.co.innbucks.loans.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Logs every call made through the shared {@code RestTemplate} (Ndasenda and InnBucks).
 *
 * <p>At INFO only the method, the URI (user-info dropped, sensitive query values
 * masked), the status and the duration are written — a failed call at WARN. Headers
 * and bodies are DEBUG only, and even then pass through {@link HttpLogRedactor}:
 * this traffic carries the Ndasenda password grant and security code, the InnBucks
 * login and the token it returns, bearer tokens, {@code X-Api-Key} and the
 * applicant's KYC, and the log files are kept for 180 days.
 *
 * <p>Logging never throws and never costs the caller its response: the body is read
 * only when DEBUG is on, and then the caller is handed a copy that replays it.
 */
@Component
public class LoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        logRequestDetail(request, body);
        final long started = System.nanoTime();
        final ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException ex) {
            logFailure(request, started, ex);
            throw ex;
        }
        return logResponse(request, response, started);
    }

    private void logRequestDetail(HttpRequest request, byte[] body) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        try {
            logger.debug("REQUEST {} {} | Headers: {} | Body: {}",
                    request.getMethod(),
                    HttpLogRedactor.redactUri(request.getURI()),
                    HttpLogRedactor.redactHeaders(request.getHeaders()),
                    HttpLogRedactor.redactBody(body, contentTypeOf(request.getHeaders())));
        } catch (RuntimeException ex) {
            logger.debug("Could not log request detail: {}", ex.getClass().getSimpleName());
        }
    }

    private void logFailure(HttpRequest request, long started, Exception ex) {
        try {
            logger.warn("{} {} failed after {} ms: {}",
                    request.getMethod(), HttpLogRedactor.redactUri(request.getURI()), elapsedMillis(started),
                    ex.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // Logging must never replace the caller's exception with its own.
        }
    }

    private ClientHttpResponse logResponse(HttpRequest request, ClientHttpResponse response, long started) {
        try {
            HttpStatusCode status = response.getStatusCode();
            String uri = HttpLogRedactor.redactUri(request.getURI());
            long elapsed = elapsedMillis(started);
            if (status.isError()) {
                logger.warn("{} {} -> {} in {} ms", request.getMethod(), uri, status.value(), elapsed);
            } else {
                logger.info("{} {} -> {} in {} ms", request.getMethod(), uri, status.value(), elapsed);
            }
        } catch (IOException | RuntimeException ex) {
            logger.info("{} completed in {} ms; status unreadable ({})",
                    request.getMethod(), elapsedMillis(started), ex.getClass().getSimpleName());
        }

        if (!logger.isDebugEnabled()) {
            return response;
        }

        final byte[] bytes;
        try {
            bytes = StreamUtils.copyToByteArray(response.getBody());
        } catch (IOException | RuntimeException ex) {
            // Nothing readable to replay; the caller meets the same failure reading it.
            logger.debug("RESPONSE body not logged: {}", ex.getClass().getSimpleName());
            return response;
        }
        final ClientHttpResponse replayable = new ReplayableResponse(response, bytes);
        try {
            logger.debug("RESPONSE {} {} | Headers: {} | Body: {}",
                    request.getMethod(),
                    HttpLogRedactor.redactUri(request.getURI()),
                    HttpLogRedactor.redactHeaders(response.getHeaders()),
                    HttpLogRedactor.redactBody(bytes, contentTypeOf(response.getHeaders())));
        } catch (RuntimeException ex) {
            logger.debug("Could not log response detail: {}", ex.getClass().getSimpleName());
        }
        return replayable;
    }

    private static MediaType contentTypeOf(HttpHeaders headers) {
        try {
            return headers == null ? null : headers.getContentType();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /**
     * The response with its body already read into memory, so reading it for the log
     * does not consume it for the caller — whether or not the request factory buffers.
     */
    static final class ReplayableResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] body;

        ReplayableResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
