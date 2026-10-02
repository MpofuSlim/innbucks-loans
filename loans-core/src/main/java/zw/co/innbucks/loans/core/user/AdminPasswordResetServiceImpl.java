package zw.co.innbucks.loans.core.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.notifications.EmailNotificationClient;
import zw.co.innbucks.loans.core.notifications.NotificationChannel;
import zw.co.innbucks.loans.core.notifications.NotificationDeliveryException;
import zw.co.innbucks.loans.core.notifications.SmsNotificationClient;
import zw.co.innbucks.loans.core.notifications.WhatsAppNotificationClient;

/**
 * Super-admin password reset. Distinct from the best-effort self-service reset:
 * here delivery of the temporary password is a hard precondition, so it uses the
 * notification clients directly (which throw on failure) rather than the
 * fire-and-forget {@code NotificationService} facade, and only persists the new
 * password once delivery has succeeded. WhatsApp is the default channel and falls
 * back to SMS; the wording is {@link PortalCredentialMessages}'.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminPasswordResetServiceImpl implements AdminPasswordResetService {

    private static final PortalCredentialMessages.Reason REASON = PortalCredentialMessages.Reason.ADMIN_RESET;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SmsNotificationClient smsNotificationClient;
    private final EmailNotificationClient emailNotificationClient;
    private final WhatsAppNotificationClient whatsAppNotificationClient;
    private final PortalCredentialMessages messages;

    @Override
    @Transactional
    public PasswordResetOutcome resetPassword(Long userId, NotificationChannel channel) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User " + userId + " not found"));

        String temporaryPassword = TemporaryPasswordGenerator.generate();

        // Deliver first — a failure here throws NotificationDeliveryException and
        // aborts before the password is changed.
        NotificationChannel sentBy = deliver(user, channel == null ? NotificationChannel.WHATSAPP : channel,
                temporaryPassword);

        user.setPassword(passwordEncoder.encode(temporaryPassword));
        user.setTemporaryPassword(true);
        // Whoever held the account's old sessions (the usual reason for a reset) is signed out, and a
        // lock from failed attempts is lifted: this is how a locked-out user gets back in early.
        user.bumpTokenVersion();
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        log.info("Super-admin reset password for user {} delivered via {}",
                user.getUsername(), sentBy);

        return new PasswordResetOutcome(UserResponse.from(user), sentBy);
    }

    /**
     * Each channel gets the wording it can carry; SMS and WhatsApp keep the gateway's reply out of the log. WhatsApp
     * falls back to SMS. Returns the channel that delivered it.
     */
    private NotificationChannel deliver(User user, NotificationChannel channel, String temporaryPassword) {
        String firstName = user.getFirstName();
        String username = user.getUsername();
        if (channel == NotificationChannel.EMAIL) {
            if (!StringUtils.hasText(user.getEmail())) {
                throw new ValidationException("User %s has no email address on file".formatted(user.getUsername()));
            }
            emailNotificationClient.sendEmail(user.getEmail(), messages.emailSubject(REASON),
                    messages.email(REASON, firstName, username, temporaryPassword), null, messages.signInButton(),
                    true);
            return NotificationChannel.EMAIL;
        }
        if (!StringUtils.hasText(user.getMobileNumber())) {
            throw new ValidationException("User %s has no mobile number on file".formatted(user.getUsername()));
        }
        String to = MsisdnUtils.toE164(user.getMobileNumber());
        if (channel == NotificationChannel.WHATSAPP) {
            try {
                whatsAppNotificationClient.sendCustomNotification(to,
                        messages.whatsApp(REASON, firstName, username, temporaryPassword), true);
                return NotificationChannel.WHATSAPP;
            } catch (NotificationDeliveryException whatsAppFailure) {
                log.warn("Password reset for {} not delivered by WhatsApp, sending by SMS instead: {}",
                        user.getUsername(), whatsAppFailure.getMessage());
            }
        }
        smsNotificationClient.sendSms(to, messages.sms(REASON, firstName, username, temporaryPassword), null, true);
        return NotificationChannel.SMS;
    }
}
