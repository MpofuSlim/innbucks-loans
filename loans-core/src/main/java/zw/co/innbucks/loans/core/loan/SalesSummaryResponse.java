package zw.co.innbucks.loans.core.loan;

import java.math.BigDecimal;

/**
 * An originator's disbursed loans over a period: how many, their principal, and the agent
 * commission they earned. Built by the query in {@link LoanRepository#salesSummary}; an empty
 * period is zeros, not nulls.
 */
public record SalesSummaryResponse(long loanCount, BigDecimal totalPrincipal, BigDecimal totalAgentCommission) {

    public SalesSummaryResponse(BigDecimal totalPrincipal, BigDecimal totalAgentCommission, Long loanCount) {
        this(loanCount == null ? 0 : loanCount,
                totalPrincipal == null ? BigDecimal.ZERO : totalPrincipal,
                totalAgentCommission == null ? BigDecimal.ZERO : totalAgentCommission);
    }
}
