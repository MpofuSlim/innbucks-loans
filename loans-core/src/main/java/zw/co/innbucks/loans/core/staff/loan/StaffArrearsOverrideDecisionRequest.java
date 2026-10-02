package zw.co.innbucks.loans.core.staff.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The checker's decision on a proposed arrears override. A rejection needs a reason. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffArrearsOverrideDecisionRequest {

    @NotNull(message = "Decision is required (APPROVED or REJECTED)")
    @Schema(example = "APPROVED")
    private StaffArrearsOverrideDecision decision;

    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(description = "Required when rejecting", example = "Settlement confirmed with Finance")
    private String comment;
}
