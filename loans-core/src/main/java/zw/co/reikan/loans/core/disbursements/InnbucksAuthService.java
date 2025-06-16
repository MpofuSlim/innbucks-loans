package zw.co.reikan.loans.core.disbursements;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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

    private final RestTemplate restTemplate;

    private final InnbucksParameters parameters;

    @Cacheable(value = "innbucks-access-token-cache", unless = "#result == null or #result.isEmpty()")
    public String getAccessToken() {

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

    /**
     * Refreshes the access token by evicting the current token from the cache.
     * This forces a new token to be fetched the next time getAccessToken() is called.
     * 
     * @return The new access token
     */
    @CacheEvict(value = "innbucks-access-token-cache", allEntries = true)
    public String refreshToken() {
        return getAccessToken();
    }
}
