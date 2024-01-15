package zw.co.reikan.loans.core.notifications;

public interface NotificationService {
    void sendSms(String mobileNumber, String text);

    void sendSms(Sms sms);
}
