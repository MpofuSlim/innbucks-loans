package zw.co.innbucks.loans.core.notifications;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import zw.co.innbucks.loans.core.config.OutboundHttp;

/**
 * RestClient for the external WhatsApp notification gateway. Reached via an
 * explicit base URL (never {@code lb://} / discovery) with an {@code x-api-key}
 * header, mirroring the ticketing platform's WhatsApp client.
 *
 * <p>Draws its connections from the service's one pool ({@link OutboundHttp}) and keeps its
 * own connect/read timeouts; the contract tests build it through this same method.
 */
@Configuration
@EnableConfigurationProperties(WhatsAppProperties.class)
public class WhatsAppClientConfig {

    @Bean("whatsAppRestClient")
    public RestClient whatsAppRestClient(WhatsAppProperties properties, OutboundHttp outboundHttp) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl() == null ? "" : properties.getBaseUrl())
                .requestFactory(outboundHttp.requestFactory(
                        properties.getConnectTimeoutMs(), properties.getReadTimeoutMs()))
                .build();
    }
}
