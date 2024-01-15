package zw.co.reikan.loans.core.ndasenda;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NdasendaDeductionsBatchRequest {
    @JsonProperty("id")
    private String id;
    @JsonProperty("recordsCount")
    private Integer recordsCount;
    @JsonProperty("totalAmount")
    private Integer totalAmountInCents;
    @JsonProperty("securityToken")
    private String securityToken;
    @JsonProperty("deductionCode")
    private String deductionCode;
    @JsonProperty("status")
    private DeductionBatchStatus status;
    @JsonProperty("creationDate")
    private String creationDate;
    @JsonProperty("records")
    private List<NdasendaDeduction> deductions;
}
