package zw.co.reikan.nanoloansweb.disbursements;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class InnbucksAuthResponse {
    @JsonProperty("responseCode")
    private String responseCode;
    @JsonProperty("responseDescription")
    private String responseDescription;
    @JsonProperty("accessToken")
    private String accessToken;
    @JsonProperty("accessExpiry")
    private String accessExpiry;
}
