package zw.co.innbucks.loans.core.notifications;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Config for the InnBucks public notification API (SMS and email), the same API
 * and credentials the ticketing fleet sends through. SMS goes to
 * {@code POST /api/notification/sms} and email to {@code POST /api/notification/email},
 * after a {@code POST /auth/third-party} login; auth is an {@code X-Api-Key}
 * header plus a bearer token.
 */
@Data
@ConfigurationProperties(prefix = "innbucks-notify")
public class InnbucksNotifyProperties {
    /** Gateway root, e.g. https://staging.innbucks.co.zw (no trailing path). */
    private String baseUrl;
    /** Sent as the X-Api-Key header on login + every call. */
    @ToString.Exclude
    private String apiKey;
    /** Third-party client login username. */
    private String username;
    /** Third-party client login password. */
    @ToString.Exclude
    private String password;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 20000;
    /** Fallback token lifetime when the JWT carries no parseable exp. */
    private Duration tokenTtl = Duration.ofMinutes(8);
    /**
     * Email as the InnBucks branded HTML ({@link BrandedEmailRenderer}), as the ticketing fleet sends Foundry's: on by
     * default, the gateway renders it. False sends plain text closed with {@link EmailSignature}. Either way the same
     * body goes over SMTP when that is on.
     */
    private boolean htmlEnabled = true;
    /** A hosted logo for the branded header (clients block data: URIs); blank draws the logo in CSS. */
    private String logoUrl;
}
