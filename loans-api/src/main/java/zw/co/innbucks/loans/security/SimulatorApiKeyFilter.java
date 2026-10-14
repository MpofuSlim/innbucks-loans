package zw.co.innbucks.loans.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorApiKey;
import zw.co.innbucks.loans.web.RequestBodyTooLargeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * STAGING ONLY: opens the GetMore simulator's test actions ({@code /lending/v1/getmore-simulator/**}) to whoever holds
 * {@code GETMORE_SIMULATOR_API_KEY} in {@code X-Api-Key}, and to nobody else. It runs in the simulator's own chain
 * ({@link ApiSecurityConfig#simulatorSecurityFilterChain}), before any controller and before the body is read:
 * <ol>
 *   <li>The key is compared in constant time ({@link SimulatorApiKey#accepts}). Wrong or missing: 401
 *       {@code {"code":"UNAUTHORIZED","message":"Invalid or missing api key"}}, and the body is never read, so a caller
 *       without the key gets nothing parsed and learns nothing about what the body should hold.</li>
 *   <li>A declared Content-Length over {@value #MAX_BODY_BYTES} bytes: 413 {@code PAYLOAD_TOO_LARGE}, unread. A body
 *       with no declared length is read through a stream that fails past the same limit, answered 413 as well.</li>
 *   <li>Then the caller holds {@code ROLE_GETMORE_SIMULATOR}, which the chain requires, and nothing else.</li>
 * </ol>
 * Not a {@code @Component}: Boot would register it as a servlet filter on every request as well. Nothing ahead of the
 * security chains reads a body either: Boot's FormContentFilter, which would read a form-encoded PUT, PATCH or DELETE
 * whole, is switched off ({@code spring.mvc.formcontent.filter.enabled: false}).
 */
@Slf4j
public class SimulatorApiKeyFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Api-Key";
    static final String SIMULATOR_ROLE = "GETMORE_SIMULATOR";
    static final String PRINCIPAL = "getmore-simulator";
    static final int MAX_BODY_BYTES = 8 * 1024;

    private final SimulatorApiKey apiKey;

    public SimulatorApiKeyFilter(SimulatorApiKey apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!apiKey.accepts(request.getHeader(API_KEY_HEADER))) {
            log.warn("GetMore simulator refused {} {} from {}: wrong or missing api key", request.getMethod(),
                    request.getRequestURI(), request.getRemoteAddr());
            SecurityErrorResponses.apiKeyRefused(response);
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            log.warn("GetMore simulator refused {} {}: a body of {} bytes", request.getMethod(),
                    request.getRequestURI(), request.getContentLengthLong());
            SecurityErrorResponses.payloadTooLarge(response, MAX_BODY_BYTES);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(PRINCIPAL, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + SIMULATOR_ROLE))));
        chain.doFilter(new CappedBody(request, MAX_BODY_BYTES), response);
    }

    /** The request with its body readable only up to {@code limit} bytes; past it, the stream throws. */
    static final class CappedBody extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        CappedBody(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CappedStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /** Counts what is read and fails the read that takes the count past {@code limit}. */
    private static final class CappedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        CappedStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int next = delegate.read();
            if (next >= 0) {
                count(1);
            }
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws RequestBodyTooLargeException {
            read += n;
            if (read > limit) {
                throw new RequestBodyTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
