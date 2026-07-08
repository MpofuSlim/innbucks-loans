package zw.co.reikan.loans.core.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.api.AdminResetPasswordRequest;
import zw.co.reikan.loans.core.api.UserDTO;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.notifications.EmailNotificationClient;
import zw.co.reikan.loans.core.notifications.NotificationChannel;
import zw.co.reikan.loans.core.notifications.SmsNotificationClient;

import java.security.SecureRandom;

/**
 * Super-admin password reset. Distinct from the best-effort self-service reset:
 * here delivery of the temporary password is a hard precondition, so it uses the
 * notification clients directly (which throw on failure) rather than the
 * fire-and-forget {@code NotificationService} facade, and only persists the new
 * password once delivery has succeeded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminPasswordResetServiceImpl implements AdminPasswordResetService {

    private static final String EMAIL_SUBJECT = "Innbucks Loans — password reset";
    private static final String MESSAGE_TEMPLATE =
            "%s, your Innbucks Loans password has been reset. Username: %s, Temporary password: %s. "
                    + "Please change it after you log in.";

    private static final char[] SPECIAL = {'#', '@', '$', '%', '&', '*', '!'};
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz".toCharArray();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SmsNotificationClient smsNotificationClient;
    private final EmailNotificationClient emailNotificationClient;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public UserDTO resetPassword(AdminResetPasswordRequest request) {
        if (request == null || !StringUtils.hasText(request.getUsername())) {
            throw new ValidationException("Username is required");
        }
        if (request.getChannel() == null) {
            throw new ValidationException("Delivery channel is required (EMAIL or SMS)");
        }

        User user = userRepository.findByUsername(request.getUsername().trim())
                .orElseThrow(() -> new ValidationException(
                        "User %s not found".formatted(request.getUsername().trim())));

        String temporaryPassword = generatePassword();
        String message = MESSAGE_TEMPLATE.formatted(
                StringUtils.hasText(user.getFirstName()) ? user.getFirstName() : user.getUsername(),
                user.getUsername(), temporaryPassword);

        // Deliver first — a failure here throws NotificationDeliveryException and
        // aborts before the password is changed.
        deliver(user, request.getChannel(), message);

        user.setPassword(passwordEncoder.encode(temporaryPassword));
        user.setTemporaryPassword(true);
        userRepository.save(user);
        log.info("Super-admin reset password for user {} delivered via {}",
                user.getUsername(), request.getChannel());

        return UserDTO.fromUser(user);
    }

    private void deliver(User user, NotificationChannel channel, String message) {
        switch (channel) {
            case EMAIL -> {
                if (!StringUtils.hasText(user.getEmail())) {
                    throw new ValidationException("User %s has no email address on file".formatted(user.getUsername()));
                }
                emailNotificationClient.sendEmail(user.getEmail(), EMAIL_SUBJECT, message, null);
            }
            case SMS -> {
                if (!StringUtils.hasText(user.getMobileNumber())) {
                    throw new ValidationException("User %s has no mobile number on file".formatted(user.getUsername()));
                }
                smsNotificationClient.sendSms(user.getMobileNumber(), message, null);
            }
        }
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(10);
        sb.append(SPECIAL[secureRandom.nextInt(SPECIAL.length)]);
        sb.append(Character.toUpperCase(ALPHA[secureRandom.nextInt(ALPHA.length)]));
        sb.append(DIGITS[secureRandom.nextInt(DIGITS.length)]);
        for (int i = 3; i < 10; i++) {
            sb.append(ALPHA[secureRandom.nextInt(ALPHA.length)]);
        }
        return sb.toString();
    }
}
