package zw.co.reikan.loans.core.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class RestConfig {

    @Bean
    public RestTemplate restTemplate(LoggingInterceptor loggingInterceptor, HttpClientConfig httpClientConfig) {
        // setOutputStreaming(false) was removed in Spring Framework 7; the
        // BufferingClientHttpRequestFactory wrapper below provides the same
        // request/response buffering (needed by the logging interceptor).
        final SimpleClientHttpRequestFactory simpleClientHttpRequestFactory = new SimpleClientHttpRequestFactory();
        // Without these, HttpURLConnection waits forever: one hung Ndasenda or InnBucks call froze
        // the job making it and, since every @Scheduled job shares one scheduler thread, all of them.
        simpleClientHttpRequestFactory.setConnectTimeout(
                millis("http.client.connect-timeout", httpClientConfig.getConnectTimeout()));
        simpleClientHttpRequestFactory.setReadTimeout(
                millis("http.client.read-timeout", httpClientConfig.getReadTimeout()));
        final BufferingClientHttpRequestFactory
                bufferingClientHttpRequestFactory = new BufferingClientHttpRequestFactory(simpleClientHttpRequestFactory);
        RestTemplate restTemplate = new RestTemplate(bufferingClientHttpRequestFactory);
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(loggingInterceptor);
        restTemplate.setInterceptors(interceptors);
        return restTemplate;
    }

    /**
     * Refused at boot rather than applied: to HttpURLConnection 0 means "no timeout", which is
     * exactly the hang these settings exist to prevent.
     */
    private static Duration millis(String property, int value) {
        if (value <= 0) {
            throw new IllegalStateException(property + " must be a positive number of milliseconds, but is "
                    + value + " (0 would mean waiting forever)");
        }
        return Duration.ofMillis(value);
    }

}
