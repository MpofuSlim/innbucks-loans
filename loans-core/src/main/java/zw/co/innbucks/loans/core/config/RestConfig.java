package zw.co.innbucks.loans.core.config;

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
        //
        // A POST is never sent twice by this stack, and it must stay that way: an InnBucks booking or
        // deposit and a Ndasenda lodgement are irreversible writes. HttpURLConnection silently
        // re-sends a POST whose connection dies before a response (sun.net.http.retryPost), but
        // never a request in streaming mode, and Spring 7's SimpleClientHttpRequest streams every
        // request that may carry a body (fixed-length, since the buffering wrapper sets
        // Content-Length). RestConfigTest pins it on the wire. One side effect of streaming: a 401
        // answering a POST arrives without its body. The status still raises
        // HttpClientErrorException.Unauthorized, which is all the token-refresh replays read.
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
