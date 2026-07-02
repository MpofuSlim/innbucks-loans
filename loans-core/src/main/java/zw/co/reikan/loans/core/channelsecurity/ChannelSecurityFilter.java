package zw.co.reikan.loans.core.channelsecurity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.idempotency.IdempotencyService;
import zw.co.reikan.loans.core.idempotency.IdempotencyService.Claim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static zw.co.reikan.loans.core.channelsecurity.ChannelSecurityProperties.Mode;

/**
 * Cross-channel security interceptor: verifies the {@code X-Channel-Signature}
 * HMAC (body + nonce + timestamp), enforces {@code Idempotency-Key} semantics
 * with cached-response replay, and applies transaction velocity limits.
 *
 * <p><b>Header contract</b> (see docs/integration/channel-signing.js):</p>
 * <pre>
 *   Idempotency-Key:     UUIDv4, unique per logical operation
 *   X-Channel-Id:        registered channel identifier
 *   X-Channel-Timestamp: epoch millis at signing time
 *   X-Channel-Nonce:     random 128-bit hex, single-use
 *   X-Channel-Signature: HMAC_SHA256(secret, channelId \n ts \n nonce \n sha256Hex(body))
 * </pre>
 *
 * <p><b>Modes:</b> MONITOR verifies + audits + replays but never rejects
 * (safe rollout on a live system); ENFORCE rejects violations. Isolation of
 * data streams is delegated to the DB-arbitrated {@link IdempotencyService}:
 * concurrent duplicates collapse onto one execution across all API nodes.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ChannelSecurityFilter extends OncePerRequestFilter {

    public static final String HDR_IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String HDR_CHANNEL_ID = "X-Channel-Id";
    public static final String HDR_TIMESTAMP = "X-Channel-Timestamp";
    public static final String HDR_NONCE = "X-Channel-Nonce";
    public static final String HDR_SIGNATURE = "X-Channel-Signature";
    public static final String HDR_IDEMPOTENT_REPLAY = "X-Idempotent-Replay";

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private final ChannelSecurityProperties properties;
    private final HmacSignatureVerifier signatureVerifier;
    private final ReplayNonceCache nonceCache;
    private final VelocityRateLimiter velocityRateLimiter;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (properties.getMode() == Mode.DISABLED || !MUTATING_METHODS.contains(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (properties.getExemptPaths().stream().anyMatch(p -> pathMatcher.match(p, path))) {
            return true;
        }
        return properties.getProtectedPaths().stream().noneMatch(p -> pathMatcher.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest rawRequest, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CachedBodyHttpServletRequest request = new CachedBodyHttpServletRequest(rawRequest);
        boolean enforce = properties.getMode() == Mode.ENFORCE;
        String channelId = request.getHeader(HDR_CHANNEL_ID);
        String actorKey = actorKey(request, channelId);

        // ── 1. HMAC signature + replay defence ─────────────────────────────
        if (!verifySignature(request, channelId, enforce, response)) {
            return; // rejection already written (ENFORCE only)
        }

        // ── 2. Transaction velocity (fraud primitive) ──────────────────────
        if (!velocityRateLimiter.recordAndCheck(actorKey)) {
            auditSecurityEvent("VELOCITY_LIMIT_TRIPPED", request, channelId, actorKey,
                    "count=" + velocityRateLimiter.currentCount(actorKey)
                            + " limit=" + properties.getVelocityLimit()
                            + " window=" + properties.getVelocityWindow());
            if (enforce) {
                reject(response, 429, "Transaction velocity limit exceeded for this account");
                return;
            }
        }

        // ── 3. Idempotency engine ──────────────────────────────────────────
        String idempotencyKey = request.getHeader(HDR_IDEMPOTENCY_KEY);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            auditSecurityEvent("IDEMPOTENCY_KEY_MISSING", request, channelId, actorKey, null);
            if (enforce) {
                reject(response, 400, "Idempotency-Key header (UUIDv4) is required for mutating calls");
                return;
            }
            chain.doFilter(request, response); // MONITOR: observed, not blocked
            return;
        }
        if (!UUID_V4.matcher(idempotencyKey).matches()) {
            auditSecurityEvent("IDEMPOTENCY_KEY_MALFORMED", request, channelId, actorKey, idempotencyKey);
            if (enforce) {
                reject(response, 400, "Idempotency-Key must be a UUIDv4");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        Claim claim = idempotencyService.begin(idempotencyKey, channelId, request.getMethod(),
                request.getRequestURI(), request.getCachedBody(), properties.getIdempotencyTtl());

        switch (claim) {
            case Claim.Replay replay -> {
                // Retried call: return the IDENTICAL cached response — business
                // logic does not run again, funds cannot disburse twice.
                var record = replay.record();
                response.setStatus(record.getResponseStatus() == null ? 200 : record.getResponseStatus());
                response.setContentType(record.getResponseContentType() == null
                        ? MediaType.APPLICATION_JSON_VALUE : record.getResponseContentType());
                response.setHeader(HDR_IDEMPOTENT_REPLAY, "true");
                if (record.getResponseBody() != null) {
                    response.getWriter().write(record.getResponseBody());
                }
                log.info("Idempotent replay for key {} ({} {})", idempotencyKey,
                        request.getMethod(), request.getRequestURI());
            }
            case Claim.InProgress ignored -> reject(response, 409,
                    "A request with this Idempotency-Key is still in progress; retry shortly");
            case Claim.PayloadMismatch ignored -> reject(response, 422,
                    "Idempotency-Key was already used with a different payload");
            case Claim.Acquired acquired -> executeAndCache(request, response, chain, acquired);
        }
    }

    /** Executes business logic once and caches the outcome for future replays. */
    private void executeAndCache(CachedBodyHttpServletRequest request, HttpServletResponse response,
                                 FilterChain chain, Claim.Acquired acquired) throws ServletException, IOException {
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        boolean businessLogicFailed = true;
        try {
            chain.doFilter(request, wrappedResponse);
            businessLogicFailed = wrappedResponse.getStatus() >= 500;
        } finally {
            if (businessLogicFailed) {
                // Transport/system failure: free the key so the client retry re-executes.
                idempotencyService.release(acquired.record());
            } else {
                String body = new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
                idempotencyService.complete(acquired.record(), wrappedResponse.getStatus(),
                        wrappedResponse.getContentType(), body);
            }
            wrappedResponse.copyBodyToResponse();
        }
    }

    private boolean verifySignature(CachedBodyHttpServletRequest request, String channelId,
                                    boolean enforce, HttpServletResponse response) throws IOException {
        String timestamp = request.getHeader(HDR_TIMESTAMP);
        String nonce = request.getHeader(HDR_NONCE);
        String signature = request.getHeader(HDR_SIGNATURE);
        String actorKey = actorKey(request, channelId);

        if (channelId == null || timestamp == null || nonce == null || signature == null) {
            auditSecurityEvent("CHANNEL_HEADERS_MISSING", request, channelId, actorKey, null);
            if (enforce) {
                reject(response, 401, "Channel authentication headers are required");
                return false;
            }
            return true; // MONITOR
        }

        String secret = properties.getSecrets().get(channelId);
        if (secret == null || secret.isBlank()) {
            auditSecurityEvent("CHANNEL_UNKNOWN", request, channelId, actorKey, null);
            if (enforce) {
                reject(response, 401, "Unknown channel");
                return false;
            }
            return true;
        }

        // Timestamp freshness bounds the replay window.
        try {
            Instant signedAt = Instant.ofEpochMilli(Long.parseLong(timestamp));
            long skewMillis = Math.abs(Instant.now().toEpochMilli() - signedAt.toEpochMilli());
            if (skewMillis > properties.getClockSkew().toMillis()) {
                auditSecurityEvent("CHANNEL_TIMESTAMP_STALE", request, channelId, actorKey,
                        "skewMillis=" + skewMillis);
                if (enforce) {
                    reject(response, 401, "Signature timestamp outside the accepted window");
                    return false;
                }
                return true;
            }
        } catch (NumberFormatException e) {
            auditSecurityEvent("CHANNEL_TIMESTAMP_MALFORMED", request, channelId, actorKey, timestamp);
            if (enforce) {
                reject(response, 400, "X-Channel-Timestamp must be epoch milliseconds");
                return false;
            }
            return true;
        }

        // Single-use nonce: a byte-perfect replay of a captured request dies here.
        if (!nonceCache.markIfFirstUse(channelId, nonce)) {
            auditSecurityEvent("CHANNEL_NONCE_REPLAYED", request, channelId, actorKey, nonce);
            if (enforce) {
                reject(response, 401, "Nonce already used (replay rejected)");
                return false;
            }
            return true;
        }

        if (!signatureVerifier.verify(secret, channelId, timestamp, nonce, request.getCachedBody(), signature)) {
            auditSecurityEvent("CHANNEL_SIGNATURE_INVALID", request, channelId, actorKey, null);
            if (enforce) {
                reject(response, 401, "Invalid channel signature");
                return false;
            }
        }
        return true;
    }

    private String actorKey(HttpServletRequest request, String channelId) {
        String principal = request.getUserPrincipal() != null
                ? request.getUserPrincipal().getName()
                : request.getRemoteAddr();
        return (channelId == null ? "unidentified" : channelId) + ':' + principal;
    }

    private void auditSecurityEvent(String eventType, HttpServletRequest request, String channelId,
                                    String actorKey, String detail) {
        log.warn("Channel security event {}: {} {} channel={} actor={} detail={} mode={}",
                eventType, request.getMethod(), request.getRequestURI(), channelId, actorKey, detail,
                properties.getMode());
        auditService.record(AuditLog.builder()
                .eventType(eventType)
                .entityType("HTTP_REQUEST")
                .entityId(request.getMethod() + " " + request.getRequestURI())
                .actorId(actorKey)
                .channelUsed(channelId)
                .detail(detail)
                .correlationId(request.getHeader(HDR_IDEMPOTENCY_KEY)));
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\",\"requestId\":\"" + UUID.randomUUID() + "\"}");
    }
}
