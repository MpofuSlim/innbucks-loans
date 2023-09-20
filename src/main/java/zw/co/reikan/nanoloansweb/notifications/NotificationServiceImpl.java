package zw.co.reikan.nanoloansweb.notifications;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final RestTemplate restTemplate;

    @Override
    @Async
    public void sendSms(String mobileNumber, String text) {
        Sms sms = Sms.builder()
                .from("BulkIT")
                .text(text)
                .to(mobileNumber)
                .build();
        sendSms(sms);
    }

    @Override
    @Async
    public void sendSms(Sms sms) {
        try {

            if (StringUtils.isEmpty(sms.getTo())) {
                log.info("Empty sms recipient");
                return;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Basic Z2F0ZXdheS1zbXMtY2xpZW50OiQwMzB2RkhpMFpuTFpmQFlNUVREeUFzWmQ=");
            HttpEntity<Sms> requestEntity = new HttpEntity<>(sms, headers);
            restTemplate.exchange("https://api.bulkit.co.zw/sms", HttpMethod.POST, requestEntity, Sms.class);
        } catch (Exception ex) {
            log.error("Error:", ex);
        }
    }
}
