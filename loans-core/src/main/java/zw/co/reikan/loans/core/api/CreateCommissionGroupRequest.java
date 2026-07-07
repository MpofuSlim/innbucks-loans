package zw.co.reikan.loans.core.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCommissionGroupRequest {
    private String name;
    private BigDecimal agentCommission;
    private BigDecimal providerCommission;

    /** When true, the commissions are percentages and must sum to 100. */
    private boolean percentage;
}
