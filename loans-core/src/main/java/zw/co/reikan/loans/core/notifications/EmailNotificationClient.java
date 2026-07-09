package zw.co.reikan.loans.core.notifications;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends a single plain-text email through the InnBucks public notification API:
 * {@code POST /api/notification/email}. Auth (X-Api-Key + bearer) is delegated to
 * {@link NotificationApiAuthenticator}, shared with the SMS rail.
 *
 * <p>Wire body: {@code {subject, message, reference, destinationEmail}} — plain
 * text only. Any rejection / connectivity failure surfaces as
 * {@link NotificationDeliveryException}. Never logs the subject or body (may
 * carry credentials).
 */
@Slf4j
@Component
public class EmailNotificationClient {

    private static final String EMAIL_PATH = "/api/notification/email";

    private final RestClient restClient;
    private final NotificationApiAuthenticator authenticator;

    public EmailNotificationClient(@Qualifier("innbucksNotifyRestClient") RestClient restClient,
                                   NotificationApiAuthenticator authenticator) {
        this.restClient = restClient;
        this.authenticator = authenticator;
    }

    /**
     * Send a plain-text email to a single recipient.
     *
     * @throws NotificationDeliveryException on blank input, missing config, an
     *         upstream rejection, or a connectivity failure.
     */
    public void sendEmail(String to, String subject, String message, String reference) {
        if (to == null || to.isBlank()) {
            throw new NotificationDeliveryException("Email recipient is blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new NotificationDeliveryException("Email subject is blank");
        }
        if (message == null || message.isBlank()) {
            throw new NotificationDeliveryException("Email message is blank");
        }
        authenticator.requireConfigured();
        String ref = (reference != null && !reference.isBlank())
                ? reference
                : "LOANS-EMAIL-" + UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subject", subject);
        payload.put("message", message);
        payload.put("reference", ref);
        payload.put("destinationEmail", to);

        authenticator.withAuthRetryOn401(token -> {
            try {
                restClient.post()
                        .uri(EMAIL_PATH)
                        .header(NotificationApiAuthenticator.API_KEY_HEADER, authenticator.apiKey())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 401) {
                    throw new NotificationApiAuthenticator.UnauthorizedException();
                }
                log.warn("Notification API rejected email ref={} status={} body={}",
                        ref, ex.getStatusCode(), ex.getResponseBodyAsString());
                throw new NotificationDeliveryException(
                        "Notification API rejected email: HTTP " + ex.getStatusCode().value(), ex);
            } catch (NotificationDeliveryException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn("Notification API unreachable for email ref={} error={}", ref, ex.getMessage());
                throw new NotificationDeliveryException(
                        "Notification API unreachable: " + ex.getMessage(), ex);
            }
        });
        log.info("Email notification accepted by notification API ref={}", ref);
    }
}
