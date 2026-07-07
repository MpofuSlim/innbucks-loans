package zw.co.reikan.loans.core.notifications;

/**
 * Thrown when a notification channel (SMS gateway, email API) rejects a message
 * or is unreachable, so callers can apply fallback / retry semantics. The
 * best-effort {@link NotificationService} facade catches this and logs — inline
 * business flows are never failed by a notification outage.
 */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
