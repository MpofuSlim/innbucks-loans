package zw.co.innbucks.loans.core.dashboard;

import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;

import java.math.BigDecimal;

/**
 * One group of loans sharing an SSB approval, a Credit decision and a disbursement status, with its count and its
 * sums. The dashboard folds every figure it shows out of these rows ({@code LoanRepository.dashboardGroups}), so the
 * loans table is read once per dashboard instead of once per figure. Any of the three statuses may be null; a sum is
 * null when every loan in the group has none.
 */
public record DashboardLoanGroup(LoanApprovalStatus approvalStatus,
                                 InternalApprovalStatus internalApprovalStatus,
                                 LoanDisbursementStatus disbursementStatus,
                                 long loans,
                                 BigDecimal disbursedAmount,
                                 BigDecimal agentCommission) {
}
