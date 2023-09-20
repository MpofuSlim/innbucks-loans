package zw.co.reikan.nanoloansweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import zw.co.reikan.nanoloansweb.config.HttpClientConfig;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(value = {HttpClientConfig.class})
public class NanoLoansWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(NanoLoansWebApplication.class, args);
    }

}
