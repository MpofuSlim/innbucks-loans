package zw.co.innbucks.loans.core.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Mints self-issued HS256 access tokens. The user is {@code preferred_username}
 * and the groups are {@code realm_access.roles}, which is what every consumer
 * reads — the resource-server authorities converter, {@code FindUserService} role
 * checks, the controllers reading {@code JwtAuthenticationToken}, and the portal.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    /** The sign-in methods a token was issued for (RFC 8176 {@code amr}). */
    public static final String AUTHENTICATION_METHODS_CLAIM = "amr";
    /** Signed in with a username and password: the only way to get a token here. */
    public static final String PASSWORD_AUTHENTICATION = "pwd";

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
                // Checked on every request by TokenVersionValidator: a password change bumps it.
                .claim(TokenVersionValidator.CLAIM, user.currentTokenVersion())
                // How the session signed in (RFC 8176), recorded with anything it signs (FR-SSB-013).
                .claim(AUTHENTICATION_METHODS_CLAIM, List.of(PASSWORD_AUTHENTICATION))
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long getExpiresInSeconds() {
        return jwtProperties.getExpiration() / 1000;
    }
}
