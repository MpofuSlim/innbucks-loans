package zw.co.innbucks.loans.core.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 *
 * <p><b>No transaction or database connection is held while a gateway is called.</b> The reset used to be one
 * {@code @Transactional} method, so a pooled connection sat idle inside the transaction for the whole send:
 * WhatsApp then SMS is up to ~35s, more when the notification API has to log in again. The promise above never
 * depended on that transaction: the password is written only after delivery succeeds, so a failed send has
 * nothing to roll back. So the user is read and the destination checked first, the message is sent with no
 * transaction open, and the change is then written in one short transaction on a FRESH read of the row. That
 * also means a change another writer made during the send (a credit-authority edit, the user's own password
 * change) is kept rather than refused with a version conflict, and the token-version bump starts from the
 * current value, so sessions minted during the send end too.
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
    private final PlatformTransactionManager transactionManager;

    /** Who the message goes to, read before the send so no entity or transaction is held across it. */
    private record Recipient(Long userId, String username, String firstName, String email, String e164) {}

    @Override
    public PasswordResetOutcome resetPassword(Long userId, NotificationChannel channel) {
        NotificationChannel requested = channel == null ? NotificationChannel.WHATSAPP : channel;
        Recipient recipient = prepare(userId, requested);

        String temporaryPassword = TemporaryPasswordGenerator.generate();

        // Deliver first, with no transaction open: a failure here throws NotificationDeliveryException and
        // aborts before the password is changed.
        NotificationChannel sentBy = deliver(recipient, requested, temporaryPassword);

        // Hashing is CPU time; keep it out of the transaction too.
        String hash = passwordEncoder.encode(temporaryPassword);
        UserResponse response;
        try {
            response = new TransactionTemplate(transactionManager).execute(status -> apply(userId, hash));
        } catch (RuntimeException e) {
            // The user now holds a password that was never saved. Their old password still works, so nobody is
            // locked out; the admin resets again.
            log.error("Temporary password for user {} was delivered via {} but could not be saved; the old "
                    + "password still works and the reset must be repeated", recipient.username(), sentBy, e);
            throw e;
        }
        log.info("Super-admin reset password for user {} delivered via {}", recipient.username(), sentBy);
        return new PasswordResetOutcome(response, sentBy);
    }

    /**
     * Reads the user and checks the destination for the channel exists. Every refusal (no such user, no email, no
     * mobile number) is raised here, before anything is sent.
     */
    private Recipient prepare(Long userId, NotificationChannel channel) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
        String e164 = null;
        if (channel == NotificationChannel.EMAIL) {
            if (!StringUtils.hasText(user.getEmail())) {
                throw new ValidationException("User %s has no email address on file".formatted(user.getUsername()));
            }
        } else {
            if (!StringUtils.hasText(user.getMobileNumber())) {
                throw new ValidationException("User %s has no mobile number on file".formatted(user.getUsername()));
            }
            e164 = MsisdnUtils.toE164(user.getMobileNumber());
        }
        return new Recipient(user.getId(), user.getUsername(), user.getFirstName(), user.getEmail(), e164);
    }

    /** The change itself, on a fresh read inside the caller's short transaction. */
    private UserResponse apply(Long userId, String hash) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
        user.setPassword(hash);
        user.setTemporaryPassword(true);
        // Whoever held the account's old sessions (the usual reason for a reset) is signed out, and a
        // lock from failed attempts is lifted: this is how a locked-out user gets back in early.
        user.bumpTokenVersion();
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        // Built here: it reads the user's merchant and commission group.
        return UserResponse.from(user);
    }

    /**
     * Each channel gets the wording it can carry; SMS and WhatsApp keep the gateway's reply out of the log. WhatsApp
     * falls back to SMS. Returns the channel that delivered it.
     */
    private NotificationChannel deliver(Recipient user, NotificationChannel channel, String temporaryPassword) {
        String firstName = user.firstName();
        String username = user.username();
        if (channel == NotificationChannel.EMAIL) {
            emailNotificationClient.sendEmail(user.email(), messages.emailSubject(REASON),
                    messages.email(REASON, firstName, username, temporaryPassword), null, messages.signInButton(),
                    true);
            return NotificationChannel.EMAIL;
        }
        String to = user.e164();
        if (channel == NotificationChannel.WHATSAPP) {
            try {
                whatsAppNotificationClient.sendCustomNotification(to,
                        messages.whatsApp(REASON, firstName, username, temporaryPassword), true);
                return NotificationChannel.WHATSAPP;
            } catch (NotificationDeliveryException whatsAppFailure) {
                log.warn("Password reset for {} not delivered by WhatsApp, sending by SMS instead: {}",
                        user.username(), whatsAppFailure.getMessage());
            }
        }
        smsNotificationClient.sendSms(to, messages.sms(REASON, firstName, username, temporaryPassword), null, true);
        return NotificationChannel.SMS;
    }
}
