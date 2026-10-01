package zw.co.innbucks.loans.core.staff.offer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Ends an approved override, so the member's grade limit applies again. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevokeStaffLimitOverrideRequest {

    @NotBlank(message = "Reason is required")
    @Size(max = 255, message = "Reason must be at most 255 characters")
    @Schema(example = "Salary advance repaid")
    private String reason;
}
