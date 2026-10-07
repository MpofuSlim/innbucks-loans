package zw.co.innbucks.loans.core.disbursements;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@RequiredArgsConstructor
@Service
public class InnbucksAuthService {

    static final String CACHE = "innbucks-access-token-cache";

    private final RestTemplate restTemplate;

    private final InnbucksParameters parameters;

    /** The cached token, or a fresh login when there is none (bounded and short-lived: spring.cache in application.yml). */
    @Cacheable(value = CACHE, unless = "#result == null or #result.isEmpty()")
    public String getAccessToken() {
        return login();
    }

    /**
     * Logs in again after InnBucks refused the cached token, and caches the new one in its place. The refused token is
     * evicted BEFORE the login, so a failed login leaves nothing cached and the next call logs in again; the new token
     * is PUT under {@link #getAccessToken()}'s key, so the replay that follows uses it rather than logging in a second
     * time. (This used to call {@code getAccessToken()} on itself, which skips the cache proxy: the new token was
     * returned, never cached, and evicted after, so every 401 cost two logins.)
     *
     * @return The new access token
     */
    @Caching(evict = @CacheEvict(value = CACHE, allEntries = true, beforeInvocation = true),
            put = @CachePut(value = CACHE, unless = "#result == null or #result.isEmpty()"))
    public String refreshToken() {
        return login();
    }

    private String login() {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Api-Key", parameters.getApiKey());
        headers.add("X-Trace-Id", UUID.randomUUID().toString());

        InnbucksAuthRequest authRequest = InnbucksAuthRequest.builder()
                .password(parameters.getPassword())
                .username(parameters.getUsername())
                .build();

        HttpEntity<InnbucksAuthRequest> requestEntity = new HttpEntity<>(authRequest, headers);

        ResponseEntity<InnbucksAuthResponse> responseEntity = restTemplate.exchange(
                parameters.getAuthEndpoint(),
                HttpMethod.POST,
                requestEntity,
                InnbucksAuthResponse.class);

        return responseEntity.getBody().getAccessToken();
    }
}
