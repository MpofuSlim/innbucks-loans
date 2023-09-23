package zw.co.reikan.nanoloansweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import zw.co.reikan.nanoloansweb.config.HttpClientConfig;
import zw.co.reikan.nanoloansweb.disbursements.InnbucksParameters;
import zw.co.reikan.nanoloansweb.ndasenda.NdasendaParameters;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(value = {HttpClientConfig.class, NdasendaParameters.class, InnbucksParameters.class})
@EnableCaching
public class NanoLoansWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(NanoLoansWebApplication.class, args);
    }

}
