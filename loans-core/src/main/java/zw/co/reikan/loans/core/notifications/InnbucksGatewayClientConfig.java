package zw.co.reikan.loans.core.notifications;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient for the InnBucks core gateway adapter (SMS). Distinct from the
 * public notification API used for email — this targets the host-resident
 * gateway via an explicit base URL.
 */
@Configuration
@EnableConfigurationProperties(InnbucksGatewayProperties.class)
public class InnbucksGatewayClientConfig {

    @Bean("innbucksGatewayRestClient")
    public RestClient innbucksGatewayRestClient(InnbucksGatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl() == null ? "" : properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
