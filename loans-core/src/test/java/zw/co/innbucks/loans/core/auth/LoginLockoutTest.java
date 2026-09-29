package zw.co.innbucks.loans.core.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import zw.co.innbucks.loans.core.api.LoginRequest;
import zw.co.innbucks.loans.core.api.LoginResponse;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.exception.AccountLockedException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sign-in used to allow unlimited guesses, and a password change left every earlier token working
 * for a day. These pin the ticketing user-service's rules, now applied here: 7 consecutive failures
 * lock the account for 30 minutes (423), and a password change ends the sessions before it.
 */
class LoginLockoutTest {

    private static final String PASSWORD = "Correct-Horse-9";

    private final UserRepository users = mock(UserRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private final JwtService jwtService = mock(JwtService.class);
    private AuthServiceImpl auth;
    private User user;

    @BeforeEach
    void setUp() {
        auth = new AuthServiceImpl(users, encoder, jwtService, auditService, 7, 30);
        user = new User();
        user.setId(5L);
        user.setUsername("teller1");
        user.setExternalSystemId("ext-5");
        user.setPassword(encoder.encode(PASSWORD));
        user.setTemporaryPassword(false);
        when(users.findByUsername("teller1")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(any())).thenReturn("token");
        clearInvocations(encoder);
    }

    private static LoginRequest attempt(String password) {
        return LoginRequest.builder().username("teller1").password(password).build();
    }

    @Test
    @DisplayName("a wrong password is counted, once, atomically, and answered as invalid credentials")
    void wrongPasswordIsCounted() {
        assertThatThrownBy(() -> auth.login(attempt("wrong-guess")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid username or password");

        verify(users).recordFailedLogin(5L);
        verify(users).lockIfOverLimit(eq(5L), eq(7), any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("the failure that reaches the limit locks for 30 minutes, says so (423) and is audited")
    void theSeventhFailureLocks() {
        when(users.lockIfOverLimit(eq(5L), eq(7), any(), any())).thenReturn(1);
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        AccountLockedException locked = catchLocked(() -> auth.login(attempt("wrong-guess")));

        assertThat(locked.getLockedUntil()).isBetween(before.plusMinutes(30),
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30));
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        assertThat(audit.getValue().build().getEventType()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    @DisplayName("while locked, even the right password is refused (423) and not even checked")
    void lockedRefusesWithoutCheckingThePassword() {
        LocalDateTime until = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(12);
        user.setLockedUntil(until);

        assertThat(catchLocked(() -> auth.login(attempt(PASSWORD))).getLockedUntil()).isEqualTo(until);

        verify(encoder, never()).matches(anyString(), anyString());
        verify(users, never()).recordFailedLogin(anyLong());
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    @DisplayName("a lock that has run out starts the count again, and the right password signs in")
    void expiredLockStartsAgain() {
        user.setLockedUntil(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        user.setFailedLoginAttempts(7);

        LoginResponse response = auth.login(attempt(PASSWORD));

        assertThat(response.getAccessToken()).isEqualTo("token");
        verify(users, atLeastOnce()).clearFailedLogins(5L);
    }

    @Test
    @DisplayName("a successful sign-in clears earlier failures; a clean one writes nothing")
    void successClearsFailures() {
        user.setFailedLoginAttempts(3);
        auth.login(attempt(PASSWORD));
        verify(users).clearFailedLogins(5L);

        clearInvocations(users);
        user.setFailedLoginAttempts(0);
        auth.login(attempt(PASSWORD));
        verify(users, never()).clearFailedLogins(anyLong());
    }

    @Test
    @DisplayName("an unknown username costs the same password hash as a known one, and changes nothing")
    void unknownUserHasNoTimingOracle() {
        when(users.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.login(LoginRequest.builder().username("nobody").password("guess-123").build()))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid username or password");

        verify(encoder).matches(eq("guess-123"), anyString());
        verify(users, never()).recordFailedLogin(anyLong());
        verify(users, never()).lockIfOverLimit(anyLong(), anyInt(), any(), any());
    }

    // ── Password changes ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a password change is held to the policy, not trimmed, and ends earlier sessions")
    void passwordChangeEndsEarlierSessions() {
        user.setTokenVersion(4L);

        auth.resetPassword("New-Passw0rd", "ext-5", "teller1");

        assertThat(user.getTokenVersion()).isEqualTo(5L);
        assertThat(user.getTemporaryPassword()).isFalse();
        assertThat(encoder.matches("New-Passw0rd", user.getPassword())).isTrue();
        verify(users).save(user);
    }

    @Test
    @DisplayName("a new password the policy refuses changes nothing, and says which rule")
    void policyRefusals() {
        assertRefused("short1!", "at least 8 characters");
        assertRefused(" leading-space", "must not begin or end with a space");
        assertRefused("trailing-space ", "must not begin or end with a space");
        assertRefused("x".repeat(73), "at most 72 characters");
        assertRefused("é".repeat(37), "at most 72 characters"); // 37 characters, 74 bytes: past bcrypt's limit
        assertRefused(PASSWORD, "different from the current one");
        assertRefused("", "is required");
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("the policy's edges: exactly 8 and exactly 72 bytes are accepted")
    void policyEdges() {
        PasswordPolicy.requireAcceptable("12345678");
        PasswordPolicy.requireAcceptable("y".repeat(72));
        PasswordPolicy.requireAcceptable("inner space ok");
    }

    private void assertRefused(String newPassword, String reason) {
        assertThatThrownBy(() -> auth.resetPassword(newPassword, "ext-5", "teller1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(reason);
    }

    private static AccountLockedException catchLocked(Runnable call) {
        try {
            call.run();
        } catch (AccountLockedException locked) {
            return locked;
        }
        throw new AssertionError("expected the account to be locked");
    }
}
