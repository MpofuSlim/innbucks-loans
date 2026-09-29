package zw.co.reikan.loans.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Timeouts, in milliseconds, of the shared outbound {@link org.springframework.web.client.RestTemplate}
 * built by {@link RestConfig}, which carries every Ndasenda and InnBucks call. Why the values are
 * what they are is written beside {@code http.client} in application.yml. The same defaults are
 * held here so that a context without that block (a test, an external config that leaves it out)
 * still gets bounded calls, never the JDK's wait-forever.
 *
 * <p>Registered only by {@code @EnableConfigurationProperties} on
 * {@link zw.co.reikan.loans.core.LoansCoreConfig}. It is deliberately not also a
 * {@code @Configuration}: the application's component scan then registered a second bean of this
 * type, so injecting it by type resolved only through the parameter-name fallback.
 */
@ConfigurationProperties(prefix = "http.client")
@Getter
@Setter
public class HttpClientConfig {

    /** Milliseconds to establish the TCP connection. Must be positive. */
    private int connectTimeout = 10_000;

    /** Milliseconds to wait for response data once connected. Must be positive. */
    private int readTimeout = 60_000;
}
