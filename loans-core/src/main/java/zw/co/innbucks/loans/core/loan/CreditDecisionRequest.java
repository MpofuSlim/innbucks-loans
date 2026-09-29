package zw.co.innbucks.loans.core.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Credit's decision on a loan. Every decision needs a reason code and a comment (FR-PBL-027). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreditDecisionRequest {

    @NotNull(message = "Decision is required (APPROVED, REJECTED or RETURNED)")
    @Schema(description = "APPROVED, REJECTED, or RETURNED to send it back for more information", example = "APPROVED")
    private InternalApprovalStatus decision;

    /** One of the active codes for this decision: see {@code GET /credit-reason-codes}. */
    @NotBlank(message = "Reason code is required")
    @Size(max = 64, message = "Reason code must be at most 64 characters")
    @Schema(description = "An active reason code for this decision", example = "APPROVE_WITHIN_POLICY")
    private String reasonCode;

    /** The reviewer's note, kept for staff; never sent to the customer. */
    @NotBlank(message = "Comment is required")
    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(example = "Payslip and deduction capacity verified")
    private String comment;
}
