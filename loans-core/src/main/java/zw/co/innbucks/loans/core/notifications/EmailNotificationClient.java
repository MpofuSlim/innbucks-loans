package zw.co.innbucks.loans.core.notifications;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends one email the way the ticketing fleet sends Foundry's: the InnBucks branded HTML ({@link BrandedEmailRenderer},
 * or plain text closed with {@link EmailSignature} when HTML is off), over SMTP (Amazon SES) under loans' own sender
 * name when that is on ({@link SmtpEmailSender}), else, or when SMTP fails, through the InnBucks public notification
 * API: {@code POST /api/notification/email}, {@code {subject, message, reference, destinationEmail}}, auth (X-Api-Key +
 * bearer) delegated to {@link NotificationApiAuthenticator}, shared with the SMS rail.
 *
 * <p>Any rejection or connectivity failure on the last path tried surfaces as {@link NotificationDeliveryException}.
 * Never logs the subject or body; for a message that carries a credential ({@code withheld}), not an upstream reply or
 * an exception's text either, since a refusal can quote the request back.
 */
@Slf4j
@Component
public class EmailNotificationClient {

    private static final String EMAIL_PATH = "/api/notification/email";

    private final RestClient restClient;
    private final NotificationApiAuthenticator authenticator;
    private final InnbucksNotifyProperties properties;
    /** SMTP first when on; null in the contract tests that pin the notification API's wire format. */
    private final SmtpEmailSender smtpEmailSender;

    @Autowired
    public EmailNotificationClient(@Qualifier("innbucksNotifyRestClient") RestClient restClient,
                                   NotificationApiAuthenticator authenticator, InnbucksNotifyProperties properties,
                                   SmtpEmailSender smtpEmailSender) {
        this.restClient = restClient;
        this.authenticator = authenticator;
        this.properties = properties;
        this.smtpEmailSender = smtpEmailSender;
    }

    /** Notification API only, for the contract tests that pin its wire format. */
    public EmailNotificationClient(RestClient restClient, NotificationApiAuthenticator authenticator,
                                   InnbucksNotifyProperties properties) {
        this(restClient, authenticator, properties, null);
    }

    /**
     * Sends a plain-text message as the branded email.
     *
     * @throws NotificationDeliveryException on blank input, missing config, an upstream rejection, or a connectivity
     *                                       failure
     */
    public void sendEmail(String to, String subject, String message, String reference) {
        sendEmail(to, subject, message, reference, null, false);
    }

    /**
     * As {@link #sendEmail(String, String, String, String)}, with an optional button under the body (an {@code https}
     * link the message also carries in its text, for clients that strip buttons and the plain-text path), and
     * {@code withheld} true for a message carrying a credential, so nothing upstream sends back is logged.
     */
    public void sendEmail(String to, String subject, String message, String reference,
                          BrandedEmailRenderer.CallToAction callToAction, boolean withheld) {
        if (to == null || to.isBlank()) {
            throw new NotificationDeliveryException("Email recipient is blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new NotificationDeliveryException("Email subject is blank");
        }
        if (message == null || message.isBlank()) {
            throw new NotificationDeliveryException("Email message is blank");
        }
        boolean html = properties.isHtmlEnabled();
        String body = html ? BrandedEmailRenderer.render(subject, message, properties.getLogoUrl(), callToAction)
                : EmailSignature.appendTo(message);

        // SMTP first when this cell has it: the only path where we write the From header, so the only way the message
        // shows loans' own name. The notification API stays the fallback, so a broken SES setup degrades to it rather
        // than dropping the message.
        if (smtpEmailSender != null && smtpEmailSender.isEnabled()) {
            try {
                smtpEmailSender.send(to, subject, body, html);
                log.info("Email delivered over SMTP");
                return;
            } catch (RuntimeException e) {
                log.warn("SMTP email delivery failed, falling back to the notification API: {}",
                        withheld ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        authenticator.requireConfigured();
        String ref = (reference != null && !reference.isBlank())
                ? reference
                : "LOANS-EMAIL-" + UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        // The notification API refuses a subject outside plain ASCII (400 "Invalid subject"), which the ticketing fleet
        // hit twice with an interpolated name; the body takes Unicode, so it keeps its typography.
        payload.put("subject", SmsTextSanitizer.toGsmSafe(subject));
        payload.put("message", body);
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
                log.warn("Notification API rejected email ref={} status={} body={}", ref, ex.getStatusCode(),
                        withheld ? "<withheld>" : ex.getResponseBodyAsString());
                throw new NotificationDeliveryException(
                        "Notification API rejected email: HTTP " + ex.getStatusCode().value(), ex);
            } catch (NotificationDeliveryException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn("Notification API unreachable for email ref={} error={}", ref,
                        withheld ? ex.getClass().getSimpleName() : ex.getMessage());
                throw new NotificationDeliveryException(
                        "Notification API unreachable: " + ex.getMessage(), ex);
            }
        });
        log.info("Email notification accepted by notification API ref={}", ref);
    }
}
