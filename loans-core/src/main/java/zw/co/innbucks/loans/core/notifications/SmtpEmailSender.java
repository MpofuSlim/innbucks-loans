package zw.co.innbucks.loans.core.notifications;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Sends email straight over SMTP (Amazon SES), as the ticketing fleet sends Foundry's, instead of through the InnBucks
 * notification API.
 *
 * <p>The notification API writes the {@code From} header itself, so every product riding it arrives under one display
 * name. Sending our own message lets loans present its own, {@code InnBucks Lending <banking@innbucks.co.zw>}: the
 * address stays the platform's verified SES identity, only the name is ours.
 *
 * <p>Inactive unless {@code loans.mail.enabled=true}, a sender address is set AND a {@code JavaMailSender} exists
 * (Spring builds one only when {@code spring.mail.host} is set). {@link #isEnabled()} lets the caller choose this path
 * or the notification API. Failures throw {@link NotificationDeliveryException}, as the notification API client does,
 * so the caller can fall back to it.
 */
@Slf4j
@Component
public class SmtpEmailSender {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailProperties properties;

    public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
        if (isEnabled()) {
            log.info("[startup] Email goes over SMTP as {}, falling back to the notification API", describeFrom());
        } else {
            log.info("[startup] Email goes through the notification API (SMTP is off: loans.mail.enabled, its"
                    + " sender and spring.mail.host are not all set)");
        }
    }

    /** Whether this cell sends its own mail; false leaves the notification API as the path. */
    public boolean isEnabled() {
        return properties.isEnabled() && properties.getFrom() != null && !properties.getFrom().isBlank()
                && mailSender.getIfAvailable() != null;
    }

    /**
     * Sends {@code body} as it is: the branded HTML or the signed plain text the caller would send through the
     * notification API, so the transport never changes what lands in the inbox.
     *
     * @param html true when {@code body} is HTML, false for plain text
     * @throws NotificationDeliveryException SMTP is not configured, or the send failed
     */
    public void send(String to, String subject, String body, boolean html) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new NotificationDeliveryException("SMTP is not configured (no spring.mail.host)");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, html);
            sender.send(message);
        } catch (UnsupportedEncodingException e) {
            throw new NotificationDeliveryException("Unable to encode the sender name: " + e.getMessage(), e);
        } catch (RuntimeException | MessagingException e) {
            throw new NotificationDeliveryException("SMTP delivery failed: " + e.getMessage(), e);
        }
    }

    /**
     * {@code InnBucks Lending <banking@innbucks.co.zw>} with a sender name, the bare address without. Gmail prefers a
     * saved contact's name over the header, so a tester who has the address saved under another name keeps seeing that
     * name: check the raw header ("Show original") before changing the setting.
     */
    InternetAddress fromAddress() throws UnsupportedEncodingException {
        String name = properties.getSenderName();
        return new InternetAddress(properties.getFrom(), name == null || name.isBlank() ? null : name,
                StandardCharsets.UTF_8.name());
    }

    private String describeFrom() {
        try {
            return fromAddress().toUnicodeString();
        } catch (UnsupportedEncodingException e) {
            return properties.getFrom();
        }
    }
}
