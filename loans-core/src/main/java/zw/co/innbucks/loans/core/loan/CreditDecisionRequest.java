package zw.co.innbucks.loans.core.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreditDecisionRequest {

    @NotNull(message = "Decision is required (APPROVED or REJECTED)")
    @Schema(description = "APPROVED or REJECTED", example = "APPROVED")
    private InternalApprovalStatus decision;

    /** The reviewer's note, kept for staff; never sent to the customer. */
    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(example = "Payslip and deduction capacity verified")
    private String comment;
}
