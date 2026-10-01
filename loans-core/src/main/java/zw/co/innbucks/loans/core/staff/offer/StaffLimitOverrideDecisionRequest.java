package zw.co.innbucks.loans.core.staff.offer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The checker's decision on a proposed limit override. A rejection needs a reason. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffLimitOverrideDecisionRequest {

    @NotNull(message = "Decision is required (APPROVED or REJECTED)")
    @Schema(example = "APPROVED")
    private StaffLimitOverrideDecision decision;

    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(description = "Required when rejecting", example = "Confirmed with Payroll")
    private String comment;
}
