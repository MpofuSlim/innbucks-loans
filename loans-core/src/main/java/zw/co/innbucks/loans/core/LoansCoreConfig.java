package zw.co.innbucks.loans.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import zw.co.innbucks.loans.core.auth.JwtProperties;
import zw.co.innbucks.loans.core.config.HttpClientConfig;
import zw.co.innbucks.loans.core.disbursements.InnbucksParameters;
import zw.co.innbucks.loans.core.document.DocumentUploadProperties;
import zw.co.innbucks.loans.core.draft.LoanApplicationDraftProperties;
import zw.co.innbucks.loans.core.ndasenda.NdasendaParameters;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferProperties;


@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(value = {HttpClientConfig.class,
        NdasendaParameters.class,
        InnbucksParameters.class,
        JwtProperties.class,
        DocumentUploadProperties.class,
        LoanApplicationDraftProperties.class,
        StaffOfferProperties.class})
@EnableCaching
public class LoansCoreConfig {
}
