package zw.co.reikan.nanoloansweb.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "http.client")
@Getter
@Setter
public class HttpClientConfig {

    int connectTimeout;
    int readTimeout;
}
