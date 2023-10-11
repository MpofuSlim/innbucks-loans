package zw.co.reikan.loans.core.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class RestConfig {

    @Bean
    public RestTemplate restTemplate(LoggingInterceptor loggingInterceptor) {
        final SimpleClientHttpRequestFactory simpleClientHttpRequestFactory = new SimpleClientHttpRequestFactory();
        simpleClientHttpRequestFactory.setOutputStreaming(false);
        final BufferingClientHttpRequestFactory
                bufferingClientHttpRequestFactory = new BufferingClientHttpRequestFactory(simpleClientHttpRequestFactory);
        RestTemplate restTemplate = new RestTemplate(bufferingClientHttpRequestFactory);
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(loggingInterceptor);
        restTemplate.setInterceptors(interceptors);
        return restTemplate;
    }

}
