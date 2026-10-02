package zw.co.innbucks.loans.core.notifications;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A copy of the ticketing fleet's user-service test of the same name, on loans' examples. Pins the two things the
 * SMTP path exists to get right:
 *
 * <ol>
 *   <li>the {@code From} header carries our display name, which is the whole
 *       point of not using the shared notification API; and</li>
 *   <li>the body is delivered <b>byte-identical</b> to what the API path sends,
 *       in the right content type — switching transport must not change what
 *       the recipient sees.</li>
 * </ol>
 *
 * <p>Pure JUnit + Mockito: a real {@link JavaMailSenderImpl} builds a real
 * {@link MimeMessage} (so the headers are genuinely encoded), but nothing ever
 * opens a socket — the send itself is captured on a mock.
 */
class SmtpEmailSenderTest {

    private static final String HTML_BODY =
            "<html><body><h1>Your InnBucks Lending password has been reset</h1><p>Use these to sign in.</p></body></html>";

    private record Fixture(SmtpEmailSender sender, JavaMailSender mail) {}

    private static Fixture fixture(boolean enabled, String from, String senderName) {
        JavaMailSender mail = mock(JavaMailSender.class);
        // Real session so createMimeMessage() returns a real, encodable message.
        when(mail.createMimeMessage()).thenAnswer(inv -> new JavaMailSenderImpl().createMimeMessage());

        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mail);

        MailProperties props = new MailProperties();
        props.setEnabled(enabled);
        props.setFrom(from);
        props.setSenderName(senderName);
        return new Fixture(new SmtpEmailSender(provider, props), mail);
    }

    /**
     * The message as the SMTP server would receive it. saveChanges() is what
     * writes the MIME headers out of the DataHandler — without it
     * getContentType() reports the "text/plain" default no matter what the body
     * actually is, so a content-type assertion would pass for the wrong reason.
     */
    private static MimeMessage captureSent(JavaMailSender mail) throws Exception {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        MimeMessage msg = sent.getValue();
        msg.saveChanges();
        return msg;
    }

    @Test
    void from_carriesTheConfiguredDisplayName() throws Exception {
        Fixture f = fixture(true, "banking@innbucks.co.zw", "InnBucks Lending");

        f.sender().send("tawanda@innbucks.co.zw", "Your InnBucks Lending password has been reset", HTML_BODY, true);

        MimeMessage msg = captureSent(f.mail());
        // This is the header that decides what the inbox list shows.
        assertThat(msg.getHeader("From", null)).isEqualTo("InnBucks Lending <banking@innbucks.co.zw>");
        assertThat(msg.getHeader("To", null)).isEqualTo("tawanda@innbucks.co.zw");
        assertThat(msg.getSubject()).isEqualTo("Your InnBucks Lending password has been reset");
    }

    @Test
    void from_withoutSenderName_isTheBareAddress() throws Exception {
        Fixture f = fixture(true, "banking@innbucks.co.zw", "   ");

        f.sender().send("a@example.com", "subject", HTML_BODY, true);

        assertThat(captureSent(f.mail()).getHeader("From", null)).isEqualTo("banking@innbucks.co.zw");
    }

    @Test
    void htmlBody_isSentAsHtml_unchanged() throws Exception {
        Fixture f = fixture(true, "banking@innbucks.co.zw", "InnBucks Lending");

        f.sender().send("a@example.com", "subject", HTML_BODY, true);

        MimeMessage msg = captureSent(f.mail());
        assertThat(msg.getContentType()).startsWith("text/html");
        // Byte-identical: the branded template must survive the transport swap.
        assertThat(bodyOf(msg)).contains(HTML_BODY);
    }

    @Test
    void plainBody_isSentAsPlainText_soNewlinesSurvive() throws Exception {
        // The bug this guards: sending plain text as text/html collapses every
        // newline, which is how the escalation emails would regress.
        String plain = "These items have waited past their escalation point:\n\nCredit decision: loan 000000012\n\n"
                + "- The InnBucks Lending Team";
        Fixture f = fixture(true, "banking@innbucks.co.zw", "InnBucks Lending");

        f.sender().send("credit1@innbucks.co.zw", "Overdue: Credit decision, loan 000000012", plain, false);

        MimeMessage msg = captureSent(f.mail());
        assertThat(msg.getContentType()).startsWith("text/plain");
        assertThat(bodyOf(msg)).contains(plain);
    }

    @Test
    void disabled_whenFlagOff() {
        assertThat(fixture(false, "banking@innbucks.co.zw", "InnBucks Lending").sender().isEnabled()).isFalse();
    }

    @Test
    void disabled_whenFromMissing() {
        assertThat(fixture(true, "  ", "InnBucks Lending").sender().isEnabled()).isFalse();
    }

    @Test
    void disabled_whenNoMailSenderBean() {
        // spring.mail.host unset -> Boot never creates a JavaMailSender.
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        MailProperties props = new MailProperties();
        props.setEnabled(true);
        props.setFrom("banking@innbucks.co.zw");

        SmtpEmailSender sender = new SmtpEmailSender(empty, props);

        assertThat(sender.isEnabled()).isFalse();
        assertThatThrownBy(() -> sender.send("a@example.com", "s", "b", false))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void transportFailure_surfacesAsNotificationDeliveryException() {
        // Lets the caller fall back to the notification API instead of losing
        // the message.
        Fixture f = fixture(true, "banking@innbucks.co.zw", "InnBucks Lending");
        doThrow(new org.springframework.mail.MailSendException("SES rejected"))
                .when(f.mail()).send(any(MimeMessage.class));

        assertThatThrownBy(() -> f.sender().send("a@example.com", "s", HTML_BODY, true))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("SMTP delivery failed");
    }

    /**
     * The DECODED body. Reading the serialized message instead would compare
     * against quoted-printable, where a long HTML line comes back soft-wrapped
     * with "=\r\n" and never matches the source template.
     */
    private static String bodyOf(MimeMessage msg) throws Exception {
        return String.valueOf(msg.getContent());
    }
}
