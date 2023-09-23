package zw.co.reikan.nanoloansweb.ndasenda;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NdasendaDeduction {
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
    @JsonProperty("payrollNumber")
    private String payrollNumber;
    @JsonProperty("name")
    private String name;
    @JsonProperty("surname")
    private String surname;
    @JsonProperty("amount")
    private Integer amountInCents;
    @JsonProperty("totalAmount")
    private Integer totalAmountInCents;
}
