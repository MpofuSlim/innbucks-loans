package zw.co.innbucks.loans.core.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import zw.co.innbucks.loans.core.config.SingleFlightTokenCache;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared authentication for the InnBucks notification API. Both the email
 * ({@code /api/notification/email}) and SMS ({@code /api/notification/sms}) rails
 * are fronted by the same auth: an {@code X-Api-Key} header plus a bearer token
 * obtained from {@code POST /auth/third-party} (cached until the JWT {@code exp},
 * refreshed once on a 401).
 *
 * <p>The token lives in a {@link SingleFlightTokenCache}: a sender holding a valid token
 * never waits for someone else's login, at most one login runs at a time, and N
 * concurrent 401s cost one login. It used to be a {@code synchronized} method that
 * held its monitor across the login, so one slow login stalled every SMS and email.
 *
 * <p>Login runs against the notification API base URL ({@code innbucks-notify});
 * the resulting token is presented on whichever rail the caller targets.
 * Credentials come from {@link InnbucksNotifyProperties}.
 */
@Slf4j
@Component
public class NotificationApiAuthenticator {

    private static final String LOGIN_PATH = "/auth/third-party";
    public static final String API_KEY_HEADER = "X-Api-Key";
    /** A token is not presented in its last 30 s, as before: clock skew and the call's own duration. */
    private static final Duration EXPIRY_SAFETY = Duration.ofSeconds(30);
    /** A refresh starts this long before that, while callers keep using the current token. */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final InnbucksNotifyProperties properties;
    // Constructed internally: Spring Boot 4 auto-configures a Jackson 3 mapper, so
    // no Jackson-2 ObjectMapper bean exists to inject. Only tiny responses parsed here.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SingleFlightTokenCache tokens;

    public NotificationApiAuthenticator(@Qualifier("innbucksNotifyRestClient") RestClient restClient,
                                        InnbucksNotifyProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        this.tokens = new SingleFlightTokenCache(this::login, REFRESH_MARGIN,
                SingleFlightTokenCache.waitBound(properties.getConnectTimeoutMs(), properties.getReadTimeoutMs()),
                () -> new NotificationDeliveryException("Timed out waiting for the notification API login"));
    }

    /** The {@code X-Api-Key} value presented on every notification-platform call. */
    public String apiKey() {
        return properties.getApiKey();
    }

    /** Fails fast if the notification credentials are not configured. */
    public void requireConfigured() {
        if (isBlank(properties.getBaseUrl()) || isBlank(properties.getApiKey())
                || isBlank(properties.getUsername()) || isBlank(properties.getPassword())) {
            throw new NotificationDeliveryException(
                    "Notification API is not configured — set innbucks-notify base-url/api-key/username/password");
        }
    }

    /**
     * Runs an authenticated call with a valid bearer token; if the callback throws
     * {@link UnauthorizedException} (i.e. the rail returned 401), forces one token
     * refresh and replays the call exactly once.
     */
    public <T> T withAuthRetryOn401(Function<String, T> call) {
        String token = tokens.get();
        try {
            return call.apply(token);
        } catch (UnauthorizedException first) {
            log.info("Notification platform returned 401 — refreshing token and replaying once");
            try {
                // Logs in again only if no other sender has already replaced the refused token.
                return call.apply(tokens.refreshAfterRejection(token));
            } catch (UnauthorizedException second) {
                throw new NotificationDeliveryException(
                        "Notification platform rejected our credentials twice (401)");
            }
        }
    }

    /** One login. Runs outside any lock; {@link SingleFlightTokenCache} keeps it to one at a time. */
    private SingleFlightTokenCache.Token login() {
        try {
            String raw = restClient.post()
                    .uri(LOGIN_PATH)
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", properties.getUsername(),
                            "password", properties.getPassword()))
                    .retrieve()
                    .body(String.class);
            Map<String, Object> parsed = parseJson(raw);
            Object token = parsed.get("accessToken");
            if (token == null || token.toString().isBlank()) {
                throw new NotificationDeliveryException("Notification API login returned no accessToken");
            }
            String accessToken = token.toString();
            Instant tokenExpiry = deriveExpiry(accessToken).minus(EXPIRY_SAFETY);
            log.info("Notification API login succeeded; token cached until {}", tokenExpiry);
            return new SingleFlightTokenCache.Token(accessToken, tokenExpiry);
        } catch (RestClientResponseException e) {
            // Status only: a refused login's body can echo what was sent.
            log.warn("Notification API rejected login status={}", e.getStatusCode());
            throw new NotificationDeliveryException(
                    "Notification API login failed: HTTP " + e.getStatusCode().value(), e);
        } catch (NotificationDeliveryException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Notification API unreachable for login: {}", e.getMessage());
            throw new NotificationDeliveryException(
                    "Unable to reach the notification API for login: " + e.getMessage(), e);
        }
    }

    /** Best-effort JWT exp parse; falls back to the configured token TTL. */
    private Instant deriveExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length >= 2) {
                String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                Object exp = parseJson(payloadJson).get("exp");
                if (exp instanceof Number n) {
                    return Instant.ofEpochSecond(n.longValue());
                }
            }
        } catch (RuntimeException ignored) {
            // Opaque token — fall through to TTL.
        }
        return Instant.now().plus(properties.getTokenTtl());
    }

    private Map<String, Object> parseJson(String raw) {
        try {
            return objectMapper.readValue(raw,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new NotificationDeliveryException("Notification API returned an unparseable response", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Marker a caller throws on a 401 so {@link #withAuthRetryOn401} can replay once. */
    public static final class UnauthorizedException extends RuntimeException {
    }
}
