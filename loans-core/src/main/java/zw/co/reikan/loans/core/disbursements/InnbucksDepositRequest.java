package zw.co.reikan.loans.core.disbursements;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InnbucksDepositRequest {
    @JsonProperty("reference")
    private String reference;
    @JsonProperty("amount")
    private Integer amount;
    @JsonProperty("narration")
    private String narration;
    @JsonProperty("destinationMsisdn")
    private String destinationMsisdn;
    @JsonProperty("destinationAccount")
    private String destinationAccount;
}
