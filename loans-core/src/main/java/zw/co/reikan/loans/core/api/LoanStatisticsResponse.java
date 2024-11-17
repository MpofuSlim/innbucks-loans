package zw.co.reikan.loans.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@AllArgsConstructor
@NoArgsConstructor
public class LoanStatisticsResponse {
    private BigDecimal totalDisbursementAmount;
    private BigDecimal totalAgentCommissionAmount;
    private long loanCount;
}
