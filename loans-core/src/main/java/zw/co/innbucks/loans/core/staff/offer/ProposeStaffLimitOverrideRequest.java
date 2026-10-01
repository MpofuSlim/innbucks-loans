package zw.co.innbucks.loans.core.staff.offer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** A limit for one staff member in place of their grade's, for another credit manager to authorise (FR-SGL-011). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposeStaffLimitOverrideRequest {

    @NotBlank(message = "Employee number is required")
    @Schema(example = "E1012")
    private String employeeNumber;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.00", message = "Amount cannot be negative")
    @Digits(integer = 17, fraction = 2, message = "Amount must have at most 2 decimal places")
    @Schema(description = "The most they may borrow, in the cell's currency, instead of their grade's limit; 0 stops"
            + " offers to them", example = "150.00")
    private BigDecimal amount;

    @NotBlank(message = "Reason is required")
    @Size(max = 255, message = "Reason must be at most 255 characters")
    @Schema(description = "Why, for the checker and the audit trail", example = "Existing salary advance outstanding"
            + " until December")
    private String reason;
}
