package zw.co.innbucks.loans.core.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.notifications.EmailNotificationClient;
import zw.co.innbucks.loans.core.notifications.NotificationChannel;
import zw.co.innbucks.loans.core.notifications.SmsNotificationClient;
import zw.co.innbucks.loans.core.notifications.WhatsAppNotificationClient;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A super-admin reset is what an operator reaches for when an account is compromised or locked out.
 * It now signs out every session the old password held, and lifts a lock from failed attempts.
 */
class AdminPasswordResetSessionsTest {

    @Test
    @DisplayName("a reset ends the account's earlier sessions and lifts its lock")
    void resetEndsSessionsAndUnlocks() {
        UserRepository users = mock(UserRepository.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        User user = new User();
        user.setId(5L);
        user.setUsername("teller1");
        user.setMobileNumber("263772123123");
        user.setMerchant(new Merchant());
        user.setTokenVersion(2L);
        user.setFailedLoginAttempts(7);
        user.setLockedUntil(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        when(users.findById(5L)).thenReturn(Optional.of(user));
        AdminPasswordResetServiceImpl service = new AdminPasswordResetServiceImpl(users, new BCryptPasswordEncoder(4),
                sms, mock(EmailNotificationClient.class), mock(WhatsAppNotificationClient.class));

        service.resetPassword(5L, NotificationChannel.SMS);

        assertThat(user.getTokenVersion()).isEqualTo(3L);
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getTemporaryPassword()).isTrue();
        verify(sms).sendSms(eq("263772123123"), anyString(), eq(null));
        verify(users).save(user);
    }
}
