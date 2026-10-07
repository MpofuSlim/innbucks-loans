package zw.co.innbucks.loans.core.ndasenda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@RequiredArgsConstructor
@Service
@Slf4j
public class NdasendaAuthService {

    static final String CACHE = "ndasenda-access-token-cache";

    private final RestTemplate restTemplate;

    private final NdasendaParameters parameters;

    /** The cached token, or a fresh login when there is none (bounded and short-lived: spring.cache in application.yml). */
    @Cacheable(value = CACHE, unless = "#result == null or #result.isEmpty()")
    public String getAccessToken() {
        return login();
    }

    /**
     * Logs in again after Ndasenda refused the cached token, and caches the new one in its place: evicted BEFORE the
     * login, so a failed login leaves nothing cached, and PUT under {@link #getAccessToken()}'s key, so the replay that
     * follows uses it. (It used to call {@code getAccessToken()} on itself, which skips the cache proxy, so every 401
     * cost two logins.)
     *
     * @return The new access token
     */
    @Caching(evict = @CacheEvict(value = CACHE, allEntries = true, beforeInvocation = true),
            put = @CachePut(value = CACHE, unless = "#result == null or #result.isEmpty()"))
    public String refreshToken() {
        log.info("Refreshing Ndasenda access token");
        return login();
    }

    private String login() {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> authRequest = new LinkedMultiValueMap<>();

        authRequest.add("grant_type", parameters.getGrantType());
        authRequest.add("password", parameters.getPassword());
        authRequest.add("username", parameters.getUsername());

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(authRequest, headers);

        ResponseEntity<NdasendaAuthResponse> responseEntity = restTemplate.exchange(
                parameters.getAuthEndpoint(),
                HttpMethod.POST,
                requestEntity,
                NdasendaAuthResponse.class);

        return responseEntity.getBody().getAccessToken();
    }
}
