package zw.co.innbucks.loans.core.notifications;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import zw.co.innbucks.loans.core.MsisdnUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final SmsNotificationClient smsNotificationClient;
    private final EmailNotificationClient emailNotificationClient;
    private final WhatsAppNotificationClient whatsAppNotificationClient;

    @Override
    @Async
    public void sendSms(String mobileNumber, String text) {
        try {
            smsNotificationClient.sendSms(mobileNumber, text, null);
        } catch (NotificationDeliveryException ex) {
            log.error("SMS delivery failed: {}", ex.getMessage());
        }
    }

    @Override
    @Async
    public void sendPrivate(String mobileNumber, String whatsAppText, String smsText) {
        String to = MsisdnUtils.toE164(mobileNumber);
        try {
            whatsAppNotificationClient.sendCustomNotification(to, whatsAppText, true);
            return;
        } catch (NotificationDeliveryException ex) {
            log.warn("WhatsApp delivery to {} failed, sending by SMS instead: {}", MsisdnUtils.mask(to),
                    ex.getMessage());
        }
        try {
            smsNotificationClient.sendSms(to, smsText, null, true);
        } catch (NotificationDeliveryException ex) {
            log.error("Delivery to {} failed by WhatsApp and by SMS: {}", MsisdnUtils.mask(to), ex.getMessage());
        }
    }

    @Override
    @Async
    public void sendEmail(String to, String subject, String message) {
        try {
            emailNotificationClient.sendEmail(to, subject, message, null);
        } catch (NotificationDeliveryException ex) {
            log.error("Email delivery failed: {}", ex.getMessage());
        }
    }

    @Override
    @Async
    public void sendWhatsApp(String mobileNumber, String text) {
        try {
            whatsAppNotificationClient.sendCustomNotification(mobileNumber, text);
        } catch (NotificationDeliveryException ex) {
            log.error("WhatsApp delivery failed: {}", ex.getMessage());
        }
    }
}
