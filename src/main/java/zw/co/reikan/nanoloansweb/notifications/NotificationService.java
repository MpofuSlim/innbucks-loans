package zw.co.reikan.nanoloansweb.notifications;

public interface NotificationService {
    void sendSms(String mobileNumber, String text);

    void sendSms(Sms sms);
}
