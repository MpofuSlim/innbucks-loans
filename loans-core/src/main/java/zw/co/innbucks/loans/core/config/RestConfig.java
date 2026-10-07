package zw.co.innbucks.loans.core.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class RestConfig {

    @Bean
    public RestTemplate restTemplate(LoggingInterceptor loggingInterceptor, HttpClientConfig httpClientConfig,
                                     OutboundHttp outboundHttp) {
        // The transport is the service's one pooled httpclient5 client (OutboundHttp), with this
        // template's own timeouts from http.client.*. Without them a call would wait forever: one hung
        // Ndasenda or InnBucks call froze the job making it and, since every @Scheduled job shares one
        // scheduler thread, all of them.
        //
        // A POST is never sent twice by this stack, and it must stay that way: an InnBucks booking or
        // deposit and a Ndasenda lodgement are irreversible writes. OutboundHttp disables httpclient5's
        // automatic retries outright (its default re-sends an idempotent request after an I/O error and
        // ANY request after a 429/503), and never follows a redirect for a POST. RestConfigTest pins it
        // on the wire, for a POST with a body and one without.
        //
        // No BufferingClientHttpRequestFactory: it read every response body into memory, on every call,
        // only so the logging interceptor could read it too. The interceptor reads a body only when it
        // logs one (DEBUG) and then hands the caller a copy that replays it, so it needs no buffering
        // underneath, and a level changed at runtime is still safe. The request is buffered regardless,
        // by the interceptor chain, so it still goes out with a Content-Length; and every failure keeps
        // the exception the callers classify on. RestConfigTest pins both on the wire.
        OutboundHttp.PooledRequestFactory pooled = outboundHttp.requestFactory(
                millis("http.client.connect-timeout", httpClientConfig.getConnectTimeout()),
                millis("http.client.read-timeout", httpClientConfig.getReadTimeout()));
        RestTemplate restTemplate = new RestTemplate(pooled);
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(loggingInterceptor);
        restTemplate.setInterceptors(interceptors);
        return restTemplate;
    }

    /**
     * Refused at boot rather than applied: 0 used to mean "no timeout" to the transport, which is
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
