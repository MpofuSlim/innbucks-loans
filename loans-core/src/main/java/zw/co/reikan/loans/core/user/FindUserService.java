package zw.co.reikan.loans.core.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;

public interface FindUserService {

    Optional<User> findUserByUsername(String username);

    Optional<User> findUserExyernalSystemId(String externalSystemId);

    Long countAgentSalesConsultants(Long agentId);

    Page<User> findSalesConsultants(Long agentId, Pageable pageable);

    Optional<User> resolveUserFromAccessToken(Jwt token);

    boolean hasRole(Jwt token, String roleName);

    boolean hasAnyRole(Jwt token, List<String> roleNames);
}
