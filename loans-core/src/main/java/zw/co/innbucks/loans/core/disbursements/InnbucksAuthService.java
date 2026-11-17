package zw.co.innbucks.loans.core.disbursements;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.config.HttpClientConfig;
import zw.co.innbucks.loans.core.config.SingleFlightTokenCache;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The InnBucks login ({@code POST /auth/third-party}) and the token it returns, which every apply, inquiry and deposit
 * call presents as its bearer.
 *
 * <p>The token lives in a {@link SingleFlightTokenCache}: a caller holding a valid token never waits for someone else's
 * login, at most one login runs at a time, and N concurrent 401s cost one login. It used to be a Spring
 * {@code @Cacheable}, under which two callers missing the cache together each logged in; that was harmless only while
 * its callers were the serial scheduled jobs. The Staff Grocery Loan payout, its status check and the operators' calls
 * make it concurrent.
 */
@Slf4j
@Service
public class InnbucksAuthService {

    /** A refresh starts this long before the token stops being presented, while callers keep using it. */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);
    /** A token is not presented in its last 30 s: clock skew and the call's own duration. */
    static final Duration EXPIRY_SAFETY = Duration.ofSeconds(30);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTemplate restTemplate;
    private final InnbucksParameters parameters;
    private final Clock clock;
    private final SingleFlightTokenCache tokens;

    @Autowired
    public InnbucksAuthService(RestTemplate restTemplate, InnbucksParameters parameters, HttpClientConfig http) {
        this(restTemplate, parameters, http, Clock.systemUTC());
    }

    InnbucksAuthService(RestTemplate restTemplate, InnbucksParameters parameters, HttpClientConfig http,
                        Clock clock) {
        this.restTemplate = restTemplate;
        this.parameters = parameters;
        this.clock = clock;
        Duration ttl = parameters.getTokenTtl();
        if (ttl == null || ttl.compareTo(REFRESH_MARGIN.plus(EXPIRY_SAFETY)) <= 0) {
            throw new IllegalStateException("innbucks.token-ttl (INNBUCKS_TOKEN_TTL) must be longer than "
                    + REFRESH_MARGIN.plus(EXPIRY_SAFETY).toSeconds() + "s, or every call logs in again; it is " + ttl);
        }
        this.tokens = new SingleFlightTokenCache(this::login, REFRESH_MARGIN,
                SingleFlightTokenCache.waitBound(http.getConnectTimeout(), http.getReadTimeout()),
                () -> new InnbucksLoginException("Timed out waiting for the InnBucks login"), clock);
    }

    /** The current token, logging in first when there is none, or it is about to expire. */
    public String getAccessToken() {
        return tokens.get();
    }

    /**
     * After InnBucks answered 401 to a call made with {@code rejected}: logs in again only if that is still the cached
     * token, so concurrent 401s cost one login; otherwise returns the newer token. The refused token is never handed
     * out again, and a failed login leaves nothing cached.
     */
    public String refreshAfterRejection(String rejected) {
        return tokens.refreshAfterRejection(rejected);
    }

    /** One login. Runs outside any lock; {@link SingleFlightTokenCache} keeps it to one at a time. */
    private SingleFlightTokenCache.Token login() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(InnbucksHeaders.X_API_KEY, parameters.getApiKey());
        headers.add(InnbucksHeaders.X_TRACE_ID, UUID.randomUUID().toString());

        InnbucksAuthRequest authRequest = InnbucksAuthRequest.builder()
                .password(parameters.getPassword())
                .username(parameters.getUsername())
                .build();

        ResponseEntity<InnbucksAuthResponse> responseEntity;
        try {
            responseEntity = restTemplate.exchange(parameters.getAuthEndpoint(), HttpMethod.POST,
                    new HttpEntity<>(authRequest, headers), InnbucksAuthResponse.class);
        } catch (RestClientResponseException e) {
            // Status only: a refused login's body can echo what was sent.
            log.warn("InnBucks refused the login: HTTP {}", e.getStatusCode().value());
            throw new InnbucksLoginException("InnBucks login failed: HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            log.warn("InnBucks unreachable for the login: {}", e.getClass().getSimpleName());
            throw new InnbucksLoginException("Could not log in to InnBucks: " + e.getMessage(), e);
        }

        InnbucksAuthResponse body = responseEntity.getBody();
        String accessToken = body == null ? null : body.getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("InnBucks login answered HTTP {} without an accessToken", responseEntity.getStatusCode().value());
            throw new InnbucksLoginException("InnBucks login answered HTTP " + responseEntity.getStatusCode().value()
                    + " without an accessToken");
        }
        Instant usableUntil = expiryOf(accessToken).minus(EXPIRY_SAFETY);
        log.info("InnBucks login succeeded; its token is used until {}", usableUntil);
        return new SingleFlightTokenCache.Token(accessToken, usableUntil);
    }

    /** The JWT's {@code exp} when it has a readable one, else now plus {@code innbucks.token-ttl}. */
    private Instant expiryOf(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length >= 2) {
                JsonNode exp = JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]),
                        StandardCharsets.UTF_8)).path("exp");
                if (exp.isNumber()) {
                    return Instant.ofEpochSecond(exp.longValue());
                }
            }
        } catch (RuntimeException opaque) {
            // Not a JWT we can read: fall through to the TTL.
        }
        return clock.instant().plus(parameters.getTokenTtl());
    }
}
