package zw.co.innbucks.loans.core.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.notifications.BrandedEmailRenderer;
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
    private final RecordingTransactionManager transactions = new RecordingTransactionManager();
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
                new PortalCredentialMessages(portal), transactions);
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
        assertThat(text.getValue()).isEqualTo("Hi Tawanda, your InnBucks Lending password has been reset by an"
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
        assertThat(text.getValue()).startsWith("Hi Tawanda, your InnBucks Lending password has been reset by an"
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
        assertThat(transactions.begun()).as("no transaction is opened for a reset that was never delivered").isZero();
    }

    @Test
    @DisplayName("no transaction is open while any gateway is called; the change is saved inside one")
    void sendsRunOutsideTheTransaction_andTheSaveInsideOne() {
        doAnswer(inv -> {
            assertThat(transactions.active()).as("WhatsApp send inside a transaction").isFalse();
            throw new NotificationDeliveryException("WhatsApp gateway rejected the message: HTTP 400");
        }).when(whatsApp).sendCustomNotification(anyString(), anyString(), anyBoolean());
        doAnswer(inv -> {
            assertThat(transactions.active()).as("SMS send inside a transaction").isFalse();
            return null;
        }).when(sms).sendSms(anyString(), anyString(), any(), anyBoolean());
        doAnswer(inv -> {
            assertThat(transactions.active()).as("email send inside a transaction").isFalse();
            return null;
        }).when(email).sendEmail(anyString(), anyString(), anyString(), any(), any(), anyBoolean());
        when(users.save(any())).thenAnswer(inv -> {
            assertThat(transactions.active()).as("the save runs inside the transaction").isTrue();
            return inv.getArgument(0);
        });

        service.resetPassword(5L, null);                       // WhatsApp refused, then SMS
        service.resetPassword(5L, NotificationChannel.EMAIL);

        verify(whatsApp).sendCustomNotification(anyString(), anyString(), anyBoolean());
        verify(sms).sendSms(anyString(), anyString(), any(), anyBoolean());
        verify(email).sendEmail(anyString(), anyString(), anyString(), any(), any(), anyBoolean());
        verify(users, times(2)).save(any());
        assertThat(transactions.committed()).isEqualTo(2);
    }

    @Test
    @DisplayName("no destination on file: refused before anything is sent or written")
    void missingDestination_isRefusedBeforeAnything() {
        user.setEmail(null);
        user.setMobileNumber(null);

        assertThatThrownBy(() -> service.resetPassword(5L, NotificationChannel.EMAIL))
                .isInstanceOf(ValidationException.class).hasMessageContaining("no email address");
        assertThatThrownBy(() -> service.resetPassword(5L, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("no mobile number");

        verifyNoInteractions(sms, email, whatsApp);
        verify(users, never()).save(any());
        assertThat(transactions.begun()).isZero();
    }

    @Test
    @DisplayName("the change is applied to a fresh read: a concurrent edit is kept and the token version moves"
            + " on from the current value")
    void theSaveIsAppliedToAFreshRead() {
        User current = new User();
        current.setId(5L);
        current.setUsername("mpofuslim");
        current.setMerchant(new Merchant());
        current.setPassword("old-hash");
        current.setTokenVersion(5L);
        current.setFirstName("Changed during the send");
        when(users.findById(5L)).thenReturn(Optional.of(user), Optional.of(current));

        service.resetPassword(5L, NotificationChannel.SMS);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(current);
        assertThat(current.getTokenVersion()).isEqualTo(6L);
        assertThat(current.getFirstName()).isEqualTo("Changed during the send");
        assertThat(current.getTemporaryPassword()).isTrue();
        assertThat(user.getPassword()).as("the stale read is never written").isEqualTo("old-hash");
    }

    @Test
    @DisplayName("delivered but the save fails: the failure surfaces and the transaction rolls back")
    void deliveredButNotSaved_surfacesAndRollsBack() {
        when(users.save(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.resetPassword(5L, NotificationChannel.SMS))
                .isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");

        verify(sms).sendSms(anyString(), anyString(), any(), anyBoolean());
        assertThat(transactions.rolledBack()).isEqualTo(1);
        assertThat(transactions.committed()).isZero();
    }

    @Test
    @DisplayName("resetPassword carries no @Transactional, so no transaction can be wrapped round the send again")
    void resetPasswordIsNotTransactional() throws Exception {
        assertThat(AdminPasswordResetServiceImpl.class
                .getMethod("resetPassword", Long.class, NotificationChannel.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(AdminPasswordResetServiceImpl.class
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("SMS or EMAIL named: that channel only; the email has a sign-in button and its replies are"
            + " never logged")
    void namedChannels() {
        assertThat(service.resetPassword(5L, NotificationChannel.SMS).sentBy()).isEqualTo(NotificationChannel.SMS);
        assertThat(service.resetPassword(5L, NotificationChannel.EMAIL).sentBy()).isEqualTo(NotificationChannel.EMAIL);

        verify(sms).sendSms(eq("+263772123123"), anyString(), eq(null), eq(true));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).sendEmail(eq("tawanda@innbucks.co.zw"), eq("Your InnBucks Lending password has been reset"),
                body.capture(), eq(null),
                eq(new BrandedEmailRenderer.CallToAction("Sign in to InnBucks Lending", "https://lending.innbucks.co.zw/")),
                eq(true));
        assertThat(body.getValue()).startsWith("Hi Tawanda,\n\nYour InnBucks Lending password has been reset")
                .contains("Username: mpofuslim\n", "Sign in at: https://lending.innbucks.co.zw/\n")
                .endsWith("you will be asked to choose your own password when you sign in.");
        verifyNoInteractions(whatsApp);
    }
}
