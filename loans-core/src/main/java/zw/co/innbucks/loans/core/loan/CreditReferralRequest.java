package zw.co.innbucks.loans.core.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A credit officer's referral of a loan above their approval limit to a higher credit authority (FR-PBL-028), with what
 * they recommend. Like every credit action, it needs a reason code and a comment (FR-PBL-027).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditReferralRequest {

    @NotNull(message = "Recommendation is required (APPROVED or REJECTED)")
    @Schema(description = "What the referring officer recommends: APPROVED or REJECTED", example = "APPROVED")
    private InternalApprovalStatus recommendation;

    /** One of the active codes for the recommended decision: see {@code GET /credit-reason-codes}. */
    @NotBlank(message = "Reason code is required")
    @Size(max = 64, message = "Reason code must be at most 64 characters")
    @Schema(description = "An active reason code for the recommended decision", example = "APPROVE_WITHIN_POLICY")
    private String reasonCode;

    /** The officer's assessment, kept for staff; never sent to the customer. */
    @NotBlank(message = "Comment is required")
    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(example = "Payslip and deduction capacity verified; above my limit")
    private String comment;
}
