package zw.co.innbucks.loans.core.notifications;

/**
 * Best-effort notification facade. Delegates to the InnBucks notification API
 * (SMS and email) and WhatsApp gateway clients; delivery failures are logged, never thrown,
 * so an inline business flow is not failed by a notification outage.
 */
public interface NotificationService {

    void sendSms(String mobileNumber, String text);

    /**
     * A text that must never reach a log (a temporary password), by WhatsApp first and by SMS when WhatsApp fails:
     * each channel gets the wording it can carry, and a rejection is logged without the gateway's reply, in case the
     * reply echoes it.
     */
    void sendPrivate(String mobileNumber, String whatsAppText, String smsText);

    void sendEmail(String to, String subject, String message);

    void sendWhatsApp(String mobileNumber, String text);
}
