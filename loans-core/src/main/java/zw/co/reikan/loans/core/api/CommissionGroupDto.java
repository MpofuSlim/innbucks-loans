package zw.co.reikan.loans.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import zw.co.reikan.loans.core.commission.CommissionGroup;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
@AllArgsConstructor
public class CommissionGroupDto {
    private BigDecimal agentCommission;
    private BigDecimal providerCommission;
    private String name;
    private Long id;

    public static CommissionGroupDto fromCommissionGroup(CommissionGroup group) {
        return group == null ? null : CommissionGroupDto.builder()
                .id(group.getId())
                .name(group.getName())
                .agentCommission(group.getAgentCommission())
                .providerCommission(group.getProviderCommission())
                .build();
    }
}
