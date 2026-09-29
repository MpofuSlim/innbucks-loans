package zw.co.reikan.loans.core.disbursements;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

@Data
public class InnbucksAuthResponse {
    @JsonProperty("responseCode")
    private String responseCode;
    @JsonProperty("responseDescription")
    private String responseDescription;
    @ToString.Exclude
    @JsonProperty("accessToken")
    private String accessToken;
    @JsonProperty("accessExpiry")
    private String accessExpiry;
}
