package zw.co.reikan.loans.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import zw.co.reikan.loans.core.bulk.BulkIngestionProperties;
import zw.co.reikan.loans.core.auth.JwtProperties;
import zw.co.reikan.loans.core.config.HttpClientConfig;
import zw.co.reikan.loans.core.disbursements.InnbucksParameters;
import zw.co.reikan.loans.core.ndasenda.NdasendaParameters;


@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(value = {HttpClientConfig.class,
        NdasendaParameters.class,
        InnbucksParameters.class,
        JwtProperties.class,
        BulkIngestionProperties.class})
@EnableCaching
public class LoansCoreConfig {
}
