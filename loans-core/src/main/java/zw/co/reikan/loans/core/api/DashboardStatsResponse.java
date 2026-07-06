package zw.co.reikan.loans.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aggregated admin dashboard snapshot. Collapses the individual count/total
 * calls the console previously issued into a single, platform-wide payload.
 * The status maps are always fully populated (every enum value present, zero
 * when there are no matching loans) so the front end can render fixed tiles
 * without null-checking each key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardStatsResponse {

    private long totalLoans;

    /** Loan count keyed by {@code LoanApprovalStatus} name (NEW, PROCESSING, APPROVED, REJECTED, PAID, FAILED). */
    private Map<String, Long> loansByApprovalStatus;

    /** Loans awaiting internal approval: upstream-APPROVED and internal status PENDING. */
    private long pendingInternalApprovals;

    /** Loan count keyed by {@code LoanDisbursementStatus} name (PENDING, SUCCESS, FAILED). */
    private Map<String, Long> loansByDisbursementStatus;

    /** Sum of disbursed amounts for successfully disbursed loans. */
    private BigDecimal totalDisbursedAmount;

    /** Sum of agent commission for successfully disbursed loans. */
    private BigDecimal totalAgentCommission;

    private long merchantCount;

    private long userCount;

    private long batchCount;
}
