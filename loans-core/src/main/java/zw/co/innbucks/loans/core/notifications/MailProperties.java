package zw.co.innbucks.loans.core.notifications;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who loans' email says it is from, when it sends over SMTP (Amazon SES) as the ticketing fleet sends Foundry's. The
 * transport (host, port, credentials) is Spring's own {@code spring.mail.*}; this is named {@code loans.mail} so it
 * cannot collide with Boot's {@code MailProperties}, which owns that prefix.
 */
@Data
@ConfigurationProperties(prefix = "loans.mail")
public class MailProperties {

    /**
     * Send email over SMTP before trying the notification API. Off by default: a cell keeps the notification API until
     * it provisions SES and turns this on.
     */
    private boolean enabled = false;

    /** Envelope and header sender: an identity verified in SES for the region, or SES refuses the message. */
    private String from;

    /** The name the recipient's inbox shows, e.g. "InnBucks Lending". Blank sends the bare address. */
    private String senderName;
}
