package zw.co.innbucks.loans.core.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A reviewer's decision on an application held for payslip review. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayslipReviewRequest {

    @NotNull(message = "Outcome is required (CLEARED or CONFIRMED)")
    @Schema(description = "CLEARED releases the application to SSB; CONFIRMED upholds the suspicion and rejects it",
            example = "CLEARED")
    private PayslipReviewStatus outcome;

    /** Kept for staff; never sent to the customer. */
    @NotBlank(message = "Comment is required")
    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(example = "Same applicant re-applying after the June lodgement failed; payslip is current")
    private String comment;
}
