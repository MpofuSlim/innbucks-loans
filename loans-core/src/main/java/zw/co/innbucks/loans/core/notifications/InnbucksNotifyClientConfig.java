package zw.co.innbucks.loans.core.notifications;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import zw.co.innbucks.loans.core.config.OutboundHttp;

/**
 * RestClient for the InnBucks public notification API (SMS and email). Targets
 * the public API gateway with bearer + X-Api-Key auth handled in
 * {@link NotificationApiAuthenticator}.
 *
 * <p>Draws its connections from the service's one pool ({@link OutboundHttp}) and keeps its
 * own connect/read timeouts; the contract tests build it through this same method.
 */
@Configuration
@EnableConfigurationProperties({InnbucksNotifyProperties.class, MailProperties.class})
public class InnbucksNotifyClientConfig {

    @Bean("innbucksNotifyRestClient")
    public RestClient innbucksNotifyRestClient(InnbucksNotifyProperties properties, OutboundHttp outboundHttp) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl() == null ? "" : properties.getBaseUrl())
                .requestFactory(outboundHttp.requestFactory(
                        properties.getConnectTimeoutMs(), properties.getReadTimeoutMs()))
                .build();
    }
}
