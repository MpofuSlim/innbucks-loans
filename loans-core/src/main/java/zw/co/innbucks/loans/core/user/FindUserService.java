package zw.co.innbucks.loans.core.user;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;

public interface FindUserService {

    Optional<User> findUserByUsername(String username);

    Optional<User> findUserByExternalSystemId(String externalSystemId);

    Optional<User> resolveUserFromAccessToken(Jwt token);

    boolean hasRole(Jwt token, String roleName);

    boolean hasAnyRole(Jwt token, List<String> roleNames);
}
