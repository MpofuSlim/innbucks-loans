package zw.co.reikan.loans.core.ndasenda;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NdasendaDeduction {
    @JsonProperty("id")
    private String id;
    @JsonProperty("idNumber")
    private String idNumber;
    @JsonProperty("ecNumber")
    private String ecNumber;
    @JsonProperty("type")
    private NdasendaDeductionType type;
    @JsonProperty("reference")
    private String reference;
    @JsonProperty("startDate")
    private String startDate;
    @JsonProperty("endDate")
    private String endDate;
    @JsonProperty("amount")
    private Integer amountInCents;

    @JsonProperty("status")
    private NdasendaDeductionStatus status;

    @JsonProperty("message")
    private String message;

    private String firstName;

    private String lastName;

    private String mobileNumber;

}
