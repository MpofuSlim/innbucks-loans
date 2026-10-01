package zw.co.innbucks.loans.core.staff.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;

import java.time.LocalDateTime;

/**
 * Why a paid-out loan needs attention: its borrower is no longer ACTIVE on the register (FR-SGL-007, BRD 3.8).
 *
 * @param employmentStatus the status they moved to
 * @param action           what the loan needs, and so who was told
 * @param flaggedAt        when they moved to it
 * @param registerBatchId  the register batch that moved them
 */
@Schema(description = "Set on a paid-out loan whose borrower is no longer ACTIVE on the register; null otherwise")
public record StaffLoanEmploymentFlag(
        @Schema(description = "RESIGNED, TERMINATED, SUSPENDED or UNPAID_LEAVE", example = "RESIGNED")
        StaffEmploymentStatus employmentStatus,
        @Schema(description = "RECOVER_FROM_TERMINAL_BENEFITS (they left; Human Capital and Payroll were told) or"
                + " CREDIT_TO_DECIDE (suspended or on unpaid leave; Credit was told)",
                example = "RECOVER_FROM_TERMINAL_BENEFITS")
        EmploymentFlagAction action,
        @Schema(description = "When they moved to that status") LocalDateTime flaggedAt,
        @Schema(description = "The register batch that moved them", example = "41") Long registerBatchId) {

    static StaffLoanEmploymentFlag of(StaffLoan loan) {
        if (loan.getEmploymentFlag() == null) {
            return null;
        }
        return new StaffLoanEmploymentFlag(loan.getEmploymentFlag(), EmploymentFlagAction.of(loan.getEmploymentFlag()),
                loan.getEmploymentFlaggedAt(), loan.getEmploymentFlagBatchId());
    }
}
