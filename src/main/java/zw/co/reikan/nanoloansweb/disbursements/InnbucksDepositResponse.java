package zw.co.reikan.nanoloansweb.disbursements;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class InnbucksDepositResponse {
    @JsonProperty("stan")
    private String stan;
    @JsonProperty("authNumber")
    private String authNumber;
    @JsonProperty("processedDateTime")
    private String processedDateTime;
    @JsonProperty("responseCode")
    private Integer responseCode;
    @JsonProperty("responseMsg")
    private String responseMsg;
    @JsonProperty("amount")
    private String amount;
    @JsonProperty("reference")
    private String reference;
    @JsonProperty("debitAccountNumber")
    private String debitAccountNumber;
    @JsonProperty("creditAccountNumber")
    private String creditAccountNumber;
    @JsonProperty("currency")
    private String currency;
    @JsonProperty("currencySymbol")
    private String currencySymbol;
    @JsonProperty("available")
    private String available;
    @JsonProperty("ledger")
    private String ledger;
}
