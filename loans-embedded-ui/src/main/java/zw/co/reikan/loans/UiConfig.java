package zw.co.reikan.loans;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import zw.co.reikan.loans.core.LoansCoreConfig;

@Configuration
@Import(LoansCoreConfig.class)
public class UiConfig {
}
