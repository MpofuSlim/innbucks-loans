package zw.co.innbucks.loans.core.notifications;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import zw.co.innbucks.loans.core.MsisdnUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends SMS through the InnBucks public notification API ({@code POST /api/notification/sms}): the same API, the same
 * credentials ({@code innbucks-notify}) and the same wire body as the ticketing fleet's SMS client, and the same API
 * {@link EmailNotificationClient} sends email through. Auth (an {@code X-Api-Key} header plus a bearer from
 * {@code POST /auth/third-party}, refreshed once on a 401) is shared through {@link NotificationApiAuthenticator}.
 *
 * <p>It used to post to the InnBucks core gateway adapter ({@code /notifications/sms} on {@code INNBUCKS_GATEWAY_URL}),
 * a host that is retired, so no SMS from this service reached anyone.
 *
 * <p>Wire body: {@code {message, reference, destinationMsisdn}}. The message is made safe for the API first
 * ({@link SmsTextSanitizer}) and the number is sent in E.164. Failures are surfaced as
 * {@link NotificationDeliveryException}, so a caller can fall back to WhatsApp. Never logs the message body: it may
 * carry a temporary password.
 */
@Slf4j
@Component
public class SmsNotificationClient {

    private static final String SMS_PATH = "/api/notification/sms";

    private final RestClient restClient;
    private final NotificationApiAuthenticator authenticator;

    public SmsNotificationClient(@Qualifier("innbucksNotifyRestClient") RestClient restClient,
                                 NotificationApiAuthenticator authenticator) {
        this.restClient = restClient;
        this.authenticator = authenticator;
    }

    /**
     * Sends an SMS to {@code destination}, a Zimbabwean mobile number in any stored form. A blank input, missing
     * configuration, a non-2xx answer or a connection failure becomes a {@link NotificationDeliveryException}.
     */
    public void sendSms(String destination, String message, String reference) {
        if (destination == null || destination.isBlank()) {
            throw new NotificationDeliveryException("SMS recipient is blank");
        }
        if (message == null || message.isBlank()) {
            throw new NotificationDeliveryException("SMS message is blank");
        }
        authenticator.requireConfigured();
        String ref = (reference != null && !reference.isBlank())
                ? reference
                : "LOANS-SMS-" + UUID.randomUUID();
        String to = MsisdnUtils.toE164(destination);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", SmsTextSanitizer.toGsmSafe(message));
        body.put("reference", ref);
        body.put("destinationMsisdn", to);

        authenticator.withAuthRetryOn401(token -> {
            try {
                restClient.post()
                        .uri(SMS_PATH)
                        .header(NotificationApiAuthenticator.API_KEY_HEADER, authenticator.apiKey())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 401) {
                    throw new NotificationApiAuthenticator.UnauthorizedException();
                }
                log.warn("Notification API rejected SMS destination={} ref={} status={} body={}",
                        MsisdnUtils.mask(to), ref, ex.getStatusCode(), ex.getResponseBodyAsString());
                throw new NotificationDeliveryException(
                        "Notification API rejected SMS: HTTP " + ex.getStatusCode().value(), ex);
            } catch (NotificationDeliveryException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn("Notification API unreachable for SMS destination={} ref={} error={}",
                        MsisdnUtils.mask(to), ref, ex.getMessage());
                throw new NotificationDeliveryException(
                        "Notification API unreachable: " + ex.getMessage(), ex);
            }
        });
        log.info("SMS accepted by the notification API destination={} ref={}", MsisdnUtils.mask(to), ref);
    }
}
