package zw.co.reikan.loans.core.disbursements;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "innbucks")
public class InnbucksParameters {
    private String  username;
    private String password;
    private String authEndpoint;
    private String depositEndpoint;
    private String createLoanAccountEndpoint;
    private String loanInquiryEndpoint;
    private String apiKey;
}
