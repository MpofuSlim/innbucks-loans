package zw.co.innbucks.loans.core.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
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
    /**
     * Present, and true, only on a token minted while the user's password was a generated one (a new user, a
     * super-admin reset, the bootstrap admin). Such a token may do nothing but change that password; the API's
     * security chain refuses everything else with 403 PASSWORD_CHANGE_REQUIRED.
     *
     * <p>The claim can be trusted to match the account for as long as the token is accepted, so it needs no
     * database read of its own: the flag only ever changes together with the password, and every password change
     * (the user's own, a super-admin reset, forgot-password) bumps {@code token_version}, which ends every token
     * minted before it. Tokens minted before this claim existed carry none and stay unrestricted until they expire
     * (24 hours) or the password changes, whichever is first.</p>
     */
    public static final String TEMPORARY_PASSWORD_CLAIM = "temporary_password";
    /**
     * What a token is for: absent on a staff session; {@value #BORROWER_TOKEN_USE} on a Staff Grocery Loan borrower's,
     * which names a staff member rather than a user and reaches the borrower endpoints only.
     */
    public static final String TOKEN_USE_CLAIM = "token_use";
    public static final String BORROWER_TOKEN_USE = "borrower";
    /** The role a borrower session carries, and the only one. */
    public static final String BORROWER_ROLE = "BORROWER";
    /** On a borrower session: the staff member it is for, and the register number it was signed in from. */
    public static final String STAFF_MEMBER_CLAIM = "staff_member_id";
    public static final String MSISDN_CLAIM = "msisdn";
    /** The username a borrower session writes into the audit trail: never a user's, since a user's name cannot hold ':'. */
    public static final String BORROWER_USERNAME_PREFIX = "borrower:";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public String generateToken(User user) {
        Instant now = Instant.now();
        List<String> roles = user.getGroups() == null ? List.of()
                : user.getGroups().stream().map(UserGroup::name).toList();

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.getExpiration(), ChronoUnit.MILLIS))
                .subject(user.getExternalSystemId())
                .claim("preferred_username", user.getUsername())
                .claim("realm_access", Map.of("roles", roles))
                // Checked on every request by TokenVersionValidator: a password change bumps it.
                .claim(TokenVersionValidator.CLAIM, user.currentTokenVersion())
                // How the session signed in (RFC 8176), recorded with anything it signs (FR-SSB-013).
                .claim(AUTHENTICATION_METHODS_CLAIM, List.of(PASSWORD_AUTHENTICATION));
        if (Boolean.TRUE.equals(user.getTemporaryPassword())) {
            claims.claim(TEMPORARY_PASSWORD_CLAIM, true);
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    /**
     * A Staff Grocery Loan borrower's session (FR-SGL-025): for a staff member signed in through the SuperApp, not a
     * user. Short-lived and never refreshed. It carries how the middleware authenticated them ({@code amr}) and the
     * register number it was issued for, so {@link TokenVersionValidator} ends it the moment the member leaves or their
     * number changes.
     */
    public String generateBorrowerToken(Long staffMemberId, String employeeNumber, String msisdn,
                                        List<String> methods, long ttlSeconds) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .subject("staff-member:" + staffMemberId)
                .claim("preferred_username", BORROWER_USERNAME_PREFIX + employeeNumber)
                .claim("realm_access", Map.of("roles", List.of(BORROWER_ROLE)))
                .claim(TOKEN_USE_CLAIM, BORROWER_TOKEN_USE)
                .claim(STAFF_MEMBER_CLAIM, staffMemberId)
                .claim(MSISDN_CLAIM, msisdn)
                .claim(AUTHENTICATION_METHODS_CLAIM, methods)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Whether {@code jwt} is a borrower's session rather than a staff user's. */
    public static boolean isBorrowerToken(Jwt jwt) {
        return BORROWER_TOKEN_USE.equals(jwt.getClaimAsString(TOKEN_USE_CLAIM));
    }

    /** Whether {@code jwt} was minted on a temporary password, and so may only change it. */
    public static boolean issuedOnTemporaryPassword(Jwt jwt) {
        return Boolean.TRUE.equals(jwt.getClaimAsBoolean(TEMPORARY_PASSWORD_CLAIM));
    }

    public long getExpiresInSeconds() {
        return jwtProperties.getExpiration() / 1000;
    }
}
