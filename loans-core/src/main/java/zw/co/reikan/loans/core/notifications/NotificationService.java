package zw.co.reikan.loans.core.notifications;

/**
 * Best-effort notification facade. Delegates to the InnBucks gateway (SMS) and
 * notification API (email) clients; delivery failures are logged, never thrown,
 * so an inline business flow is not failed by a notification outage.
 */
public interface NotificationService {

    void sendSms(String mobileNumber, String text);

    void sendEmail(String to, String subject, String message);
}
