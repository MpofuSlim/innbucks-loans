package zw.co.reikan.loans.core.user;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

public interface FindUserService {
    Optional<User> findUserByUsername(String username);

    Optional<User> resolveUserFromAccessToken(Jwt token);

    boolean hasRole(Jwt token, String roleName);
}
