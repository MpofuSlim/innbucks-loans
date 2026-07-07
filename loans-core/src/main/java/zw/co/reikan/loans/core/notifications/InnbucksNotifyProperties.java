package zw.co.reikan.loans.core.notifications;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Config for the InnBucks public notification API (email). Email is delivered
 * via {@code POST /api/notification/email} after a {@code POST /auth/third-party}
 * login; auth is an {@code X-Api-Key} header plus a bearer token.
 */
@Data
@ConfigurationProperties(prefix = "innbucks-notify")
public class InnbucksNotifyProperties {
    /** Gateway root, e.g. https://staging.innbucks.co.zw (no trailing path). */
    private String baseUrl;
    /** Sent as the X-Api-Key header on login + every call. */
    private String apiKey;
    /** Third-party client login username. */
    private String username;
    /** Third-party client login password. */
    private String password;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 20000;
    /** Fallback token lifetime when the JWT carries no parseable exp. */
    private Duration tokenTtl = Duration.ofMinutes(8);
}
