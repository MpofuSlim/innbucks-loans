package zw.co.reikan.loans.core.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserGroup;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Mints self-issued HS256 access tokens. The claim shape mirrors what the
 * platform previously received from Keycloak ({@code preferred_username} and
 * {@code realm_access.roles}) so every downstream consumer — the resource-server
 * authorities converter, {@code FindUserService} role checks and the controllers
 * reading {@code JwtAuthenticationToken} — keeps working unchanged.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public String generateToken(User user) {
        Instant now = Instant.now();
        List<String> roles = user.getGroups() == null ? List.of()
                : user.getGroups().stream().map(UserGroup::name).toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.getExpiration(), ChronoUnit.MILLIS))
                .subject(user.getExternalSystemId())
                .claim("preferred_username", user.getUsername())
                .claim("realm_access", Map.of("roles", roles))
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long getExpiresInSeconds() {
        return jwtProperties.getExpiration() / 1000;
    }
}
