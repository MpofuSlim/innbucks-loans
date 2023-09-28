package zw.co.reikan.nanoloansweb.ndasenda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
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
public class NdasendaAuthServiceImpl {

    private final RestTemplate restTemplate;

    private final NdasendaParameters parameters;

    @Cacheable("ndasenda-access-token-cache")
    public String getAccessToken() {

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
