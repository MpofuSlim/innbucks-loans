package zw.co.innbucks.loans.core.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.notifications.EmailNotificationClient;
import zw.co.innbucks.loans.core.notifications.NotificationChannel;
import zw.co.innbucks.loans.core.notifications.NotificationDeliveryException;
import zw.co.innbucks.loans.core.notifications.SmsNotificationClient;
import zw.co.innbucks.loans.core.notifications.WhatsAppNotificationClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Where a reset password goes: WhatsApp unless the admin names a channel, SMS when WhatsApp fails, and the message
 * names the portal, the username, the password and the sign-in address. Delivery still comes before the change.
 */
class AdminPasswordResetDeliveryTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SmsNotificationClient sms = mock(SmsNotificationClient.class);
    private final EmailNotificationClient email = mock(EmailNotificationClient.class);
    private final WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
    private User user;
    private AdminPasswordResetServiceImpl service;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(5L);
        user.setUsername("mpofuslim");
        user.setFirstName("Tawanda");
        user.setMobileNumber("263772123123");
        user.setEmail("tawanda@innbucks.co.zw");
        user.setPassword("old-hash");
        user.setMerchant(new Merchant());
        when(users.findById(5L)).thenReturn(Optional.of(user));
        PortalProperties portal = new PortalProperties();
        portal.setSignInUrl("https://lending.innbucks.co.zw/");
        service = new AdminPasswordResetServiceImpl(users, new BCryptPasswordEncoder(4), sms, email, whatsApp,
                new PortalCredentialMessages(portal));
    }

    @Test
    @DisplayName("no channel named: WhatsApp, in labelled lines with the full sign-in address, and the password sent"
            + " is the one stored")
    void whatsAppByDefault() {
        PasswordResetOutcome outcome = service.resetPassword(5L, null);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq("+263772123123"), text.capture(), eq(true));
        verifyNoInteractions(sms, email);
        assertThat(outcome.sentBy()).isEqualTo(NotificationChannel.WHATSAPP);
        assertThat(outcome.user().username()).isEqualTo("mpofuslim");
        String password = text.getValue().replaceAll("(?s).*Temporary password: (\\S+)\n.*", "$1");
        assertThat(text.getValue()).isEqualTo("Hi Tawanda, your InnBucks Loans portal password has been reset by an"
                + " administrator.\n\nUsername: mpofuslim\nTemporary password: " + password + "\nSign in at:"
                + " https://lending.innbucks.co.zw/\n\nYou will be asked to choose your own password when you sign in.");
        assertThat(new BCryptPasswordEncoder(4).matches(password, user.getPassword())).isTrue();
    }

    @Test
    @DisplayName("WhatsApp refused: the same password goes by SMS, with the bare sign-in address, and the outcome"
            + " says SMS")
    void smsWhenWhatsAppFails() {
        doThrow(new NotificationDeliveryException("WhatsApp gateway rejected the message: HTTP 400"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString(), anyBoolean());

        PasswordResetOutcome outcome = service.resetPassword(5L, NotificationChannel.WHATSAPP);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq("+263772123123"), text.capture(), eq(null), eq(true));
        assertThat(outcome.sentBy()).isEqualTo(NotificationChannel.SMS);
        assertThat(text.getValue()).startsWith("Hi Tawanda, your InnBucks Loans portal password has been reset by an"
                        + " administrator. Your username is mpofuslim and your temporary password is ")
                .endsWith(". Please sign in at lending.innbucks.co.zw and change it immediately.");
    }

    @Test
    @DisplayName("WhatsApp and SMS both refused: the error surfaces and the old password still works")
    void bothFail() {
        doThrow(new NotificationDeliveryException("WhatsApp gateway rejected the message: HTTP 400"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString(), anyBoolean());
        doThrow(new NotificationDeliveryException("Notification API rejected SMS: HTTP 503"))
                .when(sms).sendSms(anyString(), anyString(), any(), anyBoolean());

        assertThatThrownBy(() -> service.resetPassword(5L, null)).isInstanceOf(NotificationDeliveryException.class)
                .hasMessage("Notification API rejected SMS: HTTP 503");
        assertThat(user.getPassword()).isEqualTo("old-hash");
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("SMS or EMAIL named: that channel only; the email subject carries no colon")
    void namedChannels() {
        assertThat(service.resetPassword(5L, NotificationChannel.SMS).sentBy()).isEqualTo(NotificationChannel.SMS);
        assertThat(service.resetPassword(5L, NotificationChannel.EMAIL).sentBy()).isEqualTo(NotificationChannel.EMAIL);

        verify(sms).sendSms(eq("+263772123123"), anyString(), eq(null), eq(true));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).sendEmail(eq("tawanda@innbucks.co.zw"), eq("Your InnBucks Loans portal password has been reset"),
                body.capture(), eq(null));
        assertThat(body.getValue()).startsWith("Hi Tawanda,\n\nYour InnBucks Loans portal password has been reset")
                .contains("Username: mpofuslim\n", "Sign in at: https://lending.innbucks.co.zw/\n")
                .endsWith("The InnBucks Loans Team");
        verifyNoInteractions(whatsApp);
    }
}
