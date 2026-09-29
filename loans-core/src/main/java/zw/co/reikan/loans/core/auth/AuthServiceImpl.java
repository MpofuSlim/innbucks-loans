package zw.co.reikan.loans.core.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.exception.AccountLockedException;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.merchant.MerchantMapper;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static zw.co.reikan.loans.core.user.User.SYSTEM_USER_NAME;

/**
 * Database-backed replacement for the former Keycloak service. Authenticates
 * against locally stored BCrypt credentials and issues self-signed JWTs; user
 * profiles and roles are read straight from the {@code users} table.
 */
@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid username or password";
    static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";

    private final UserRepository userRepository;
    private final MerchantMapper merchantMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final int maxFailedAttempts;
    private final Duration lockoutDuration;
    /** Compared against for an unknown username, so it costs the same hash as a known one: no timing oracle. */
    private final String unknownUserHash;

    public AuthServiceImpl(UserRepository userRepository, MerchantMapper merchantMapper,
                           PasswordEncoder passwordEncoder, JwtService jwtService, AuditService auditService,
                           @Value("${innbucks.account-lockout.max-attempts:7}") int maxFailedAttempts,
                           @Value("${innbucks.account-lockout.duration-minutes:30}") long lockoutMinutes) {
        this.userRepository = userRepository;
        this.merchantMapper = merchantMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutDuration = Duration.ofMinutes(lockoutMinutes);
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Sign-in, with the ticketing user-service's lockout: {@code max-attempts} (7) consecutive wrong
     * passwords lock the account for {@code duration-minutes} (30), answered 423 with the time it
     * ends. It used to allow unlimited guesses. A locked account's password is not even checked, so
     * the lock stops guessing rather than merely slowing it; a lock that has run out starts the count
     * again; a successful sign-in clears it. Each failure is ONE atomic increment, so concurrent
     * guesses cannot slip under the limit.
     */
    @Override
    public AuthResponse login(AuthRequest request) {
        Optional<User> found = userRepository.findByUsername(request.getUsername());
        if (found.isEmpty()) {
            passwordEncoder.matches(request.getPassword(), unknownUserHash);
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        User user = found.get();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (user.getLockedUntil() != null) {
            if (user.getLockedUntil().isAfter(now)) {
                throw new AccountLockedException(user.getLockedUntil());
            }
            userRepository.clearFailedLogins(user.getId());
        }

        if (user.getPassword() == null
                || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            recordFailure(user, now);
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        if (user.getFailedLoginAttempts() != null && user.getFailedLoginAttempts() > 0) {
            userRepository.clearFailedLogins(user.getId());
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

    /** Counts the failure; the one that reaches the limit locks the account and says so (423). */
    private void recordFailure(User user, LocalDateTime now) {
        userRepository.recordFailedLogin(user.getId());
        LocalDateTime until = now.plus(lockoutDuration);
        if (userRepository.lockIfOverLimit(user.getId(), maxFailedAttempts, until, now) == 1) {
            log.warn("ACCOUNT LOCKED: user {} after {} consecutive failed sign-ins, until {} UTC (audited)",
                    user.getUsername(), maxFailedAttempts, until);
            audit(user, until);
            throw new AccountLockedException(until);
        }
    }

    private void audit(User user, LocalDateTime until) {
        try {
            auditService.record(AuditLog.builder()
                    .eventType(ACCOUNT_LOCKED)
                    .entityType("USER").entityId(String.valueOf(user.getId()))
                    .actorId(user.getUsername()).channelUsed("portal")
                    .detail("failedAttempts>=" + maxFailedAttempts + " lockedUntil=" + until)
                    .correlationId(user.getExternalSystemId()));
        } catch (Exception ex) {
            // The lock is already saved and must stand; the WARN above is the evidence.
            log.error("Audit of the lock on user {} failed", user.getUsername(), ex);
        }
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

    /**
     * The user's own password change. Held to {@link PasswordPolicy}, must differ from the current
     * password, and ends every session minted before it: the caller (change-password) signs in again
     * with the new password and returns that token.
     */
    @Override
    public void resetPassword(String newPassword, String userId, String username) {
        PasswordPolicy.requireAcceptable(newPassword);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ValidationException("User %s not found".formatted(username)));
        if (user.getPassword() != null && passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new ValidationException("The new password must be different from the current one");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setTemporaryPassword(false);
        user.bumpTokenVersion();
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
