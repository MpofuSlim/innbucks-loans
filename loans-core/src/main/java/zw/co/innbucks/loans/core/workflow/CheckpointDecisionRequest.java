package zw.co.innbucks.loans.core.workflow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Clearing a loan at a checkpoint, or declining the application there (FR-SSB-014). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckpointDecisionRequest {

    @NotNull(message = "Outcome is required")
    @Schema(description = "CLEARED lets the loan carry on; DECLINED declines the application as a credit rejection",
            example = "CLEARED")
    private CheckpointOutcome outcome;

    @Size(max = 64, message = "Reason code must be at most 64 characters")
    @Schema(description = "Required to decline: an active credit reason code for REJECTED decisions"
            + " (GET /credit-reason-codes?decision=REJECTED)", example = "REJECT_DOCUMENTS")
    private String reasonCode;

    @NotBlank(message = "Comment is required")
    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(example = "Payout wallet confirmed with the applicant by phone")
    private String comment;
}
