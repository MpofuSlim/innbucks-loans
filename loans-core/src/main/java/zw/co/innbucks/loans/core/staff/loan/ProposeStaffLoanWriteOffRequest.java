package zw.co.innbucks.loans.core.staff.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Writes off a paid-out Staff Grocery Loan, or reverses a write-off, once someone else approves it (FR-GEN-011). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposeStaffLoanWriteOffRequest {

    @NotNull(message = "Staff loan id is required")
    @Schema(example = "151")
    private Long staffLoanId;

    @NotNull(message = "Kind is required (WRITE_OFF or WRITE_OFF_REVERSAL)")
    @Schema(example = "WRITE_OFF")
    private StaffLoanWriteOffKind kind;

    @NotBlank(message = "Reason is required")
    @Size(max = 255, message = "Reason must be at most 255 characters")
    @Schema(description = "Why, for the checker and the audit trail", example = "Resigned in October; terminal"
            + " benefits did not cover the balance and recovery has failed")
    private String reason;
}
