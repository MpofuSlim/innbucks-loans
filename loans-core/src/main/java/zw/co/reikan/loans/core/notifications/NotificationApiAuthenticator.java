package zw.co.reikan.loans.core.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared authentication for the InnBucks notification platform. Both the email
 * ({@code /api/notification/email}) and SMS ({@code /notifications/sms}) rails
 * are fronted by the same auth: an {@code X-Api-Key} header plus a bearer token
 * obtained from {@code POST /auth/third-party} (cached until the JWT {@code exp},
 * refreshed once on a 401).
 *
 * <p>Login always runs against the notification API base URL
 * ({@code innbucks-notify}); the resulting token is presented on whichever rail
 * the caller targets. Credentials come from {@link InnbucksNotifyProperties}.
 */
@Slf4j
@Component
public class NotificationApiAuthenticator {

    private static final String LOGIN_PATH = "/auth/third-party";
    public static final String API_KEY_HEADER = "X-Api-Key";

    private final RestClient restClient;
    private final InnbucksNotifyProperties properties;
    // Constructed internally: Spring Boot 4 auto-configures a Jackson 3 mapper, so
    // no Jackson-2 ObjectMapper bean exists to inject. Only tiny responses parsed here.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String accessToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public NotificationApiAuthenticator(@Qualifier("innbucksNotifyRestClient") RestClient restClient,
                                        InnbucksNotifyProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
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
        try {
            return call.apply(currentToken(false));
        } catch (UnauthorizedException first) {
            log.info("Notification platform returned 401 — refreshing token and replaying once");
            try {
                return call.apply(currentToken(true));
            } catch (UnauthorizedException second) {
                throw new NotificationDeliveryException(
                        "Notification platform rejected our credentials twice (401)");
            }
        }
    }

    private synchronized String currentToken(boolean force) {
        if (!force && accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
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
            accessToken = token.toString();
            tokenExpiry = deriveExpiry(accessToken).minusSeconds(30);
            log.info("Notification API login succeeded; token cached until {}", tokenExpiry);
            return accessToken;
        } catch (RestClientResponseException e) {
            log.warn("Notification API rejected login status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
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
