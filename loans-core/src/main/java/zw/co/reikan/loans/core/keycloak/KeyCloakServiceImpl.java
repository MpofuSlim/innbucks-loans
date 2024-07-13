package zw.co.reikan.loans.core.keycloak;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.loans.core.api.AuthRequest;
import zw.co.reikan.loans.core.api.AuthResponse;


@Service
@RequiredArgsConstructor
public class KeyCloakServiceImpl {

    private final RestTemplate restTemplate;
    private final AuthProperties authProperties;

    public AuthResponse getAccessToken(final AuthRequest authRequest) {
        final HttpHeaders headers = new HttpHeaders();

        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();

        map.add("grant_type", "password");
        map.add("client_id", authProperties.getClientId());
        map.add("password", authRequest.getPassword());
        map.add("username", authRequest.getUsername());

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(map, headers);

        ResponseEntity<AuthResponse> response = restTemplate.exchange(
                authProperties.getAuthUrl(),
                HttpMethod.POST,
                entity,
                AuthResponse.class);

        return response.getBody();
    }

    public String getLoggedInUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null) {
            Jwt token = (Jwt) auth.getPrincipal();
            return token.getClaimAsString("preferred_username");
        }
        return "SYSTEM";
    }
}
