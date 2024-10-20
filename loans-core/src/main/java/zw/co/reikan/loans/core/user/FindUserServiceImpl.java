package zw.co.reikan.loans.core.user;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FindUserServiceImpl implements FindUserService {

    private static final Logger logger = LoggerFactory.getLogger(FindUserServiceImpl.class);
    private final UserRepository userRepository;

    @Override
    public Optional<User> findUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    public Optional<User> resolveUserFromAccessToken(final Jwt token) {
        return findUserByUsername(token.getClaimAsString("preferred_username"));
    }

    @Override
    public boolean hasRole(Jwt token, String roleName) {
        if (token != null) {
            List<String> roles = token.getClaimAsStringList("realm_access.roles");
            return roles != null && roles.contains(roleName);
        }
        return false;
    }

}
