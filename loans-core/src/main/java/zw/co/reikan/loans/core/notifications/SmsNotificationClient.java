package zw.co.reikan.loans.core.notifications;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends SMS notifications through the InnBucks core gateway adapter
 * ({@code POST /notifications/sms}), which routes them to the InnBucks messenger
 * interface. The gateway is reached via an explicit URL backed by
 * {@link InnbucksGatewayProperties} and is fronted by the same auth as the
 * notification API, so calls carry an {@code X-Api-Key} header plus a bearer
 * token via the shared {@link NotificationApiAuthenticator} (refreshed once on a
 * 401).
 *
 * <p>Failures are surfaced as {@link NotificationDeliveryException}; the
 * best-effort {@link NotificationService} facade catches them so an inline
 * business flow is never failed by an SMS outage.
 *
 * <p>Never logs the message body — it may contain a temporary password.
 */
@Slf4j
@Component
public class SmsNotificationClient {

    private static final String SMS_PATH = "/notifications/sms";

    private final RestClient restClient;
    private final NotificationApiAuthenticator authenticator;

    public SmsNotificationClient(@Qualifier("innbucksGatewayRestClient") RestClient restClient,
                                 NotificationApiAuthenticator authenticator) {
        this.restClient = restClient;
        this.authenticator = authenticator;
    }

    /**
     * Dispatch an SMS to {@code destination} (E.164, e.g. {@code +263771234567}).
     * A non-2xx or a connectivity failure becomes a
     * {@link NotificationDeliveryException}.
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
        Map<String, String> body = new HashMap<>();
        body.put("destination", destination);
        body.put("message", message);
        body.put("reference", ref);
        body.put("senderId", "INNBUCKS");

        authenticator.withAuthRetryOn401(token -> {
            try {
                restClient.post()
                        .uri(SMS_PATH)
                        .header(NotificationApiAuthenticator.API_KEY_HEADER, authenticator.apiKey())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("SMS notification accepted by gateway destination={} ref={}", destination, ref);
                return null;
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 401) {
                    throw new NotificationApiAuthenticator.UnauthorizedException();
                }
                log.warn("InnBucks gateway rejected SMS destination={} ref={} status={} body={}",
                        destination, ref, ex.getStatusCode(), ex.getResponseBodyAsString());
                throw new NotificationDeliveryException(
                        "InnBucks gateway rejected SMS: HTTP " + ex.getStatusCode().value(), ex);
            } catch (NotificationDeliveryException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn("InnBucks gateway unreachable destination={} ref={} error={}",
                        destination, ref, ex.getMessage());
                throw new NotificationDeliveryException(
                        "InnBucks gateway unreachable: " + ex.getMessage(), ex);
            }
        });
    }
}
