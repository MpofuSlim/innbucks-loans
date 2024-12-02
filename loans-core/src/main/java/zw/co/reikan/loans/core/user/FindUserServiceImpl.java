package zw.co.reikan.loans.core.user;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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
    public Optional<User> findUserExternalSystemId(String externalSystemId) {
        return userRepository.findByExternalSystemId(externalSystemId);
    }

    @Override
    public Long countAgentSalesConsultants(Long agentId) {
        return userRepository.countByAgent_Id(agentId);
    }

    @Override
    public Page<User> findSalesConsultants(Long agentId, Pageable pageable) {
        return userRepository.findAllByAgent_Id(agentId, pageable);
    }

    @Override
    public Optional<User> resolveUserFromAccessToken(final Jwt token) {
        return findUserByUsername(token.getClaimAsString("preferred_username"));
    }

    @Override
    public boolean hasRole(Jwt token, String role) {
        return Optional.ofNullable(token)
                .map(t -> t.getClaim("realm_access"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(m -> (List<String>) m.get("roles"))
                .map(claims -> claims.stream().anyMatch(r -> r.equalsIgnoreCase(role)))
                .orElse(false);
    }

    @Override
    public boolean hasAnyRole(Jwt token, List<String> roles) {
        return Optional.ofNullable(token)
                .map(t -> t.getClaim("realm_access"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(m -> (List<String>) m.get("roles"))
                .map(claims -> claims.stream().anyMatch(role -> roles.stream().anyMatch(role::equalsIgnoreCase)))
                .orElse(false);
    }
}
