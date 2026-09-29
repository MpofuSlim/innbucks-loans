package zw.co.innbucks.loans.core.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One deduction already on the applicant's payslip and who it is paid to (FR-SSB-006): tax, pension,
 * medical aid, another lender. What SSB can still deduct for this loan is what these leave.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class PayslipDeduction {

    @NotBlank(message = "Deduction beneficiary is required")
    @Size(max = 255, message = "Deduction beneficiary must be at most 255 characters")
    @Schema(description = "Who the deduction is paid to", example = "ZIMRA PAYE")
    @Column(name = "beneficiary", nullable = false)
    private String beneficiary;

    @NotNull(message = "Deduction amount is required")
    @Positive(message = "Deduction amount must be greater than zero")
    @Schema(description = "Monthly amount, dollars", example = "85.00")
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;
}
