package zw.co.reikan.loans.core.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.api.AuthRequest;
import zw.co.reikan.loans.core.api.AuthResponse;
import zw.co.reikan.loans.core.api.CommissionGroupDto;
import zw.co.reikan.loans.core.api.SearchUserRequest;
import zw.co.reikan.loans.core.api.UserDTO;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.merchant.MerchantMapper;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static zw.co.reikan.loans.core.user.User.SYSTEM_USER_NAME;

/**
 * Database-backed replacement for the former Keycloak service. Authenticates
 * against locally stored BCrypt credentials and issues self-signed JWTs; user
 * profiles and roles are read straight from the {@code users} table.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid username or password";

    private final UserRepository userRepository;
    private final MerchantMapper merchantMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

        if (user.getPassword() == null
                || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }

        AuthResponse response = new AuthResponse();
        response.setAccessToken(jwtService.generateToken(user));
        response.setTokenType("Bearer");
        response.setExpiresIn(jwtService.getExpiresInSeconds());
        response.setTemporaryPassword(user.getTemporaryPassword());
        if (user.getMerchant() != null) {
            response.setMerchantName(user.getMerchant().getCompanyName());
            response.setMerchantCode(user.getMerchant().getMerchantCode());
        }
        if (user.getAgent() != null) {
            response.setAgentId(user.getAgent().getId());
            response.setAgentName(user.getAgent().getUsername());
        }
        return response;
    }

    @Override
    public User getLoggedInUser() {
        String loggedInUsername = getLoggedInUsername();
        return userRepository.findByUsername(loggedInUsername)
                .orElseThrow(() -> new ValidationException("User %s not found".formatted(loggedInUsername)));
    }

    @Override
    public String getLoggedInUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt token) {
            return token.getClaimAsString("preferred_username");
        }
        return SYSTEM_USER_NAME;
    }

    @Override
    public void resetPassword(String newPassword, String userId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ValidationException("User %s not found".formatted(username)));
        user.setPassword(passwordEncoder.encode(newPassword.trim()));
        user.setTemporaryPassword(false);
        userRepository.save(user);
    }

    @Override
    public List<UserDTO> findUsersByMerchantCode(String merchantCode) {
        return userRepository.findByMerchant_MerchantCode(merchantCode).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public List<UserDTO> search(SearchUserRequest searchUserRequest) {
        validateSearchRequest(searchUserRequest);
        return userRepository.findByUsernameContainingIgnoreCase(searchUserRequest.getSearchText()).stream()
                .skip((long) (searchUserRequest.getPageNumber() - 1) * searchUserRequest.getPageSize())
                .limit(searchUserRequest.getPageSize())
                .map(this::toDto)
                .toList();
    }

    @Override
    public UserDTO getUser(String userId) {
        return userRepository.findByExternalSystemId(userId)
                .map(this::toDto)
                .orElseThrow(() -> new ValidationException("User not found for %s".formatted(userId)));
    }

    @Override
    public void deleteUser(String userId) {
        // User rows are owned and deleted locally by the calling service; there is
        // no longer an external identity store to clean up.
        log.info("deleteUser({}) is a no-op — user accounts are managed locally", userId);
    }

    private UserDTO toDto(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setFirstName(user.getFirstName());
        dto.setLastName(user.getLastName());
        dto.setEmail(user.getEmail());
        dto.setMobileNumber(user.getMobileNumber());
        dto.setIdNumber(user.getIdNumber());
        dto.setTemporaryPassword(user.getTemporaryPassword());
        dto.setExternalSystemId(user.getExternalSystemId());
        dto.setGroups(new ArrayList<>(user.getGroups()));
        dto.setPhysicalAddress(user.getPhysicalAddress());
        dto.setAgentId(user.getAgent() == null ? null : user.getAgent().getId());
        if (user.getMerchant() != null) {
            dto.setMerchant(merchantMapper.fromMerchant(user.getMerchant()));
        }
        if (user.getCommissionGroup() != null) {
            dto.setCommissionGroup(CommissionGroupDto.fromCommissionGroup(user.getCommissionGroup()));
        }
        return dto;
    }

    private void validateSearchRequest(SearchUserRequest searchUserRequest) {
        if (searchUserRequest == null) {
            throw new ValidationException("User search criteria is required");
        }
        if (!StringUtils.hasText(searchUserRequest.getSearchText())) {
            throw new ValidationException("User search text required");
        }
        if (searchUserRequest.getPageNumber() == null) {
            throw new ValidationException("User search page number is required");
        }
        if (searchUserRequest.getPageSize() == null) {
            throw new ValidationException("User search page size is required");
        }
        if (searchUserRequest.getPageNumber() <= 0) {
            throw new ValidationException("User search page number cannot be negative or zero");
        }
        if (searchUserRequest.getPageSize() <= 0) {
            throw new ValidationException("User search page size cannot be negative or zero");
        }
    }
}
