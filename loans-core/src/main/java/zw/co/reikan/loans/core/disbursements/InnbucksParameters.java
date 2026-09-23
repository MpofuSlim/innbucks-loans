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
    /**
     * The InnBucks loan product code sent as {@code product} on the pre-approved
     * application (e.g. {@code NANOUS} in the IT team's collection). Configured
     * rather than hardcoded: it decides which InnBucks product the loan is booked
     * against, so it is a per-deployment business setting, not a code constant.
     */
    private String loanProduct;
}
