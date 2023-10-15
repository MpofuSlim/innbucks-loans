package zw.co.reikan.loans.core.user;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {

    private static final String REFRESH = "refresh";
    private static final String TOKEN_TYPE = "type";
    private static final String BEARER = "Bearer ";

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationServiceImpl.class);
    private final UserService userService;
    private final JwtService jwtService;
    @Value("${access.token.expirationTimeInSec}")
    private int accessExpirationInSec;
    @Value("${refresh.token.expirationTimeInSec}")
    private int refreshExpirationInSec;

    @Override
    public String getUsernameFromToken(String token) {
        return jwtService.extractUsername(token);
    }

    private Claims getClaims(String token) {
        return jwtService.extractAllClaims(token);
    }

    public AuthResponse refreshToken(final String authHeader) {

        final boolean hasBearerToken = !ObjectUtils.isEmpty(authHeader)
                && authHeader.startsWith(BEARER);

        if (!hasBearerToken) {
            throw new RuntimeException("Invalid token provided");
        }

        String refresh = authHeader.substring(BEARER.length());

        final Claims claims = getClaims(refresh);

        final String type = claims.get(TOKEN_TYPE, String.class);

        if (!REFRESH.equalsIgnoreCase(type)) {
            throw new RuntimeException("Invalid token provided");
        }

        final Long userId = Long.parseLong(claims.getId());

        LOG.info("Claim.userId: {}", userId);

        final User user = userService.findById(userId).orElseThrow();

        return getAuthResponse(user);

    }

    @Override
    public AuthResponse generateToken(String username) {

        final User user = userService.findActiveUserByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No user found with username: " + username));

        return getAuthResponse(user);
    }

    private AuthResponse getAuthResponse(User user) {
        final String tokenId = UUID.randomUUID().toString();
        final String refreshToken = jwtService.generateToken(user.getId().toString(), getRefreshClaims(tokenId), user);
        final String token = jwtService.generateToken(tokenId, getUserClaims(user, tokenId), user);

        return AuthResponse.builder()
                .accessToken(token)
                .refreshToken(refreshToken)
                .tokenType("Bearer ")
                .refreshExpiresIn(refreshExpirationInSec)
                .expiresIn(accessExpirationInSec)
                .build();
    }

    private Map<String, Object> getRefreshClaims(String refreshId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(TOKEN_TYPE, REFRESH);
        claims.put("rid", refreshId);
        return claims;
    }

    private Map<String, Object> getUserClaims(User user, String tokenId) {

        Map<String, Object> claims = new HashMap<>();

        claims.put("user_id", user.getId());

        claims.put("preferred_username", user.getEmail());

        claims.put("given_name", user.getEmail());

        claims.put("first_name", user.getFirstName());

        claims.put("last_name", user.getLastName());

        claims.put("email", user.getEmail());

        claims.put("mobile_number", user.getMobileNumber());

        claims.put("roles", Collections.singletonList(user.getRole().name()));

        claims.put(TOKEN_TYPE, "access");

        claims.put("rid", tokenId);

        return claims;
    }


}
