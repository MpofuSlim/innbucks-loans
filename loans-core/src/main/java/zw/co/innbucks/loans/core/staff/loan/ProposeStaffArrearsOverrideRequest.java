package zw.co.innbucks.loans.core.staff.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Lets one staff member take a Staff Grocery Loan despite a written-off balance, for another credit manager to
 * authorise (FR-SGL-014).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposeStaffArrearsOverrideRequest {

    @NotBlank(message = "Employee number is required")
    @Schema(example = "E1043")
    private String employeeNumber;

    @NotNull(message = "Valid until is required")
    @Schema(description = "The last market day the member may take a loan under it, today to 90 days ahead",
            example = "2026-10-31")
    private LocalDate validUntil;

    @NotBlank(message = "Reason is required")
    @Size(max = 255, message = "Reason must be at most 255 characters")
    @Schema(description = "Why, for the checker and the audit trail", example = "Repayment plan for the written-off"
            + " balance agreed with Finance; payroll deduction confirmed")
    private String reason;
}
