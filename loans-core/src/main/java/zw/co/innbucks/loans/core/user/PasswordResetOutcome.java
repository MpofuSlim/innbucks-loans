package zw.co.innbucks.loans.core.user;

import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.notifications.NotificationChannel;

/**
 * A reset done: the user (never the password) and the channel that delivered the temporary password, which is SMS
 * when WhatsApp was tried first and failed.
 */
public record PasswordResetOutcome(UserResponse user, NotificationChannel sentBy) {
}
