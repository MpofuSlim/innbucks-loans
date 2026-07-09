package zw.co.reikan.loans.core.api;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;

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
    @NotBlank(message = "Commission group name is required")
    private String name;
    @NotNull(message = "Agent commission is required")
    private BigDecimal agentCommission;
    @NotNull(message = "Provider commission is required")
    private BigDecimal providerCommission;
    /** When true, the commissions are percentages and must sum to 100. */
    private Boolean percentage;
}
