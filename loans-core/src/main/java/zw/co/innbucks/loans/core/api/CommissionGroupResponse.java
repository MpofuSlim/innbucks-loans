package zw.co.innbucks.loans.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.commission.CommissionGroup;

import java.math.BigDecimal;

/**
 * How a loan's commission is split between the agent and the provider.
 *
 * @param percentage when true the two commissions are percentages of the total and sum to 100;
 *                   otherwise they are amounts
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommissionGroupResponse(Long id, String name, BigDecimal agentCommission,
                                      BigDecimal providerCommission, Boolean percentage) {

    public static CommissionGroupResponse from(CommissionGroup group) {
        return group == null ? null : new CommissionGroupResponse(group.getId(), group.getName(),
                group.getAgentCommission(), group.getProviderCommission(), group.isPercentage());
    }
}
