package zw.co.reikan.loans.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import zw.co.reikan.loans.core.bulk.BulkIngestionProperties;
import zw.co.reikan.loans.core.channelsecurity.ChannelSecurityProperties;
import zw.co.reikan.loans.core.config.HttpClientConfig;
import zw.co.reikan.loans.core.disbursements.InnbucksParameters;
import zw.co.reikan.loans.core.keycloak.AuthProperties;
import zw.co.reikan.loans.core.ndasenda.NdasendaParameters;
import zw.co.reikan.loans.core.notifications.NotificationParameters;


@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(value = {HttpClientConfig.class,
        NdasendaParameters.class,
        InnbucksParameters.class,
        AuthProperties.class, NotificationParameters.class,
        ChannelSecurityProperties.class, BulkIngestionProperties.class})
@EnableCaching
public class LoansCoreConfig {
}
