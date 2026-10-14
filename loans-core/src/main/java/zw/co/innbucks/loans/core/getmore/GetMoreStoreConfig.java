package zw.co.innbucks.loans.core.getmore;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import zw.co.innbucks.loans.core.config.OutboundHttp;

/**
 * GetMore's wiring. The client is built in every mode, like the WhatsApp one; the contract tests build it through this
 * same method.
 */
@Configuration
@EnableConfigurationProperties(GetMoreProperties.class)
public class GetMoreStoreConfig {

    /**
     * On the service's one pool with GetMore's own timeouts, and with redirects OFF: a redirect would replay the
     * signed {@code Authorization} header against a path it was not signed for. No base URL: every call passes an
     * absolute {@link java.net.URI}, exactly the one it signed.
     */
    @Bean("getMoreRestClient")
    public static RestClient getMoreRestClient(GetMoreProperties properties, OutboundHttp outboundHttp) {
        return RestClient.builder()
                .requestFactory(outboundHttp.requestFactory(properties.getLive().getConnectTimeoutMs(),
                        properties.getLive().getReadTimeoutMs(), false))
                .build();
    }
}
