package zw.co.innbucks.loans.core.disbursements;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "innbucks")
public class InnbucksParameters {
    private String  username;
    @ToString.Exclude
    private String password;
    private String authEndpoint;
    private String depositEndpoint;
    private String createLoanAccountEndpoint;
    private String loanInquiryEndpoint;
    @ToString.Exclude
    private String apiKey;
    /**
     * The InnBucks loan product code sent as {@code product} on the pre-approved
     * application (e.g. {@code NANOUS} in the IT team's collection). Configured
     * rather than hardcoded: it decides which InnBucks product the loan is booked
     * against, so it is a per-deployment business setting, not a code constant.
     */
    private String loanProduct;
    /**
     * How long an InnBucks login is used when its token says nothing of its own expiry (no readable JWT {@code exp}):
     * the five minutes the old token cache kept it for.
     */
    private Duration tokenTtl = Duration.ofMinutes(5);
}
