package zw.co.innbucks.loans.config;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import zw.co.innbucks.loans.core.LoansCoreConfig;

@Configuration
@Import(LoansCoreConfig.class)
@EnableAutoConfiguration
public class LoansApiAppConfig {
}
