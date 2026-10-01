package zw.co.innbucks.loans.core.notifications;

/**
 * Best-effort notification facade. Delegates to the InnBucks notification API
 * (SMS and email) and WhatsApp gateway clients; delivery failures are logged, never thrown,
 * so an inline business flow is not failed by a notification outage.
 */
public interface NotificationService {

    void sendSms(String mobileNumber, String text);

    void sendEmail(String to, String subject, String message);

    void sendWhatsApp(String mobileNumber, String text);
}
