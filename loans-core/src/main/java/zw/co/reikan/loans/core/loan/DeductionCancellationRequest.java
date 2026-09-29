package zw.co.reikan.loans.core.loan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeductionCancellationRequest {
    /** The evidence: what was done on Ndasenda's portal, ideally with its cancellation reference. */
    @NotBlank(message = "A note on how the deduction was cancelled is required")
    @Size(max = 255, message = "Note must be at most 255 characters")
    private String note;
}
