package zw.co.reikan.nanoloansweb.disbursements;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InnbucksDepositRequest {
    @JsonProperty("reference")
    private String reference;
    @JsonProperty("amount")
    private Integer amount;
    @JsonProperty("narration")
    private String narration;
    @JsonProperty("destinationMsisdn")
    private String destinationMsisdn;
}
