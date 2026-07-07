package zw.co.reikan.loans.core.notifications;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the InnBucks core gateway adapter used for SMS. It is a
 * host-resident service, so it is reached via an explicit {@code base-url}.
 */
@Data
@ConfigurationProperties(prefix = "innbucks-gateway")
public class InnbucksGatewayProperties {
    /** Gateway base URL, e.g. http://10.0.155.69:8088 (no trailing path). */
    private String baseUrl;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}
