package zw.co.reikan.loans.core.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import zw.co.reikan.loans.core.user.UserRepository;

import java.util.Optional;

/**
 * Refuses a token minted before its user's password last changed, the ticketing fleet's
 * {@code token_version} rule. A token used to stay good for its full 24 hours after a password
 * change, so resetting a compromised account left whoever held its token signed in until it expired.
 *
 * <p>Every token carries the user's version when it was minted; a password change (the user's own,
 * or a super-admin reset) bumps it, and from then on only tokens minted at the new version pass. A
 * token from before this check existed carries no claim and reads as 0, so it stays good until its
 * user's first password change, then dies with the rest. A token whose user no longer exists is
 * refused. One indexed read per request, on the unique username.</p>
 */
@Component
@RequiredArgsConstructor
public class TokenVersionValidator implements OAuth2TokenValidator<Jwt> {

    static final String CLAIM = "token_version";

    private final UserRepository userRepository;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        if (username == null) {
            return refused("The token names no user");
        }
        Optional<Long> current = userRepository.findTokenVersionByUsername(username);
        if (current.isEmpty()) {
            return refused("The token's user no longer exists");
        }
        if (presented(jwt) != current.get()) {
            return refused("The token was issued before the password was last changed; sign in again");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static long presented(Jwt jwt) {
        Object claim = jwt.getClaims().get(CLAIM);
        return claim instanceof Number number ? number.longValue() : 0L;
    }

    private static OAuth2TokenValidatorResult refused(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
