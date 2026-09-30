package zw.co.innbucks.loans.core.turnaround;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A new service level for one stage (FR-PBL-030). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateServiceLevelRequest {

    @NotNull(message = "Target hours is required")
    @Min(value = 1, message = "Target hours must be at least 1")
    @Max(value = 720, message = "Target hours must be at most 720")
    @Schema(description = "Hours within which the stage should be done; later is overdue", example = "24")
    private Integer targetHours;

    @NotNull(message = "Escalation hours is required")
    @Min(value = 1, message = "Escalation hours must be at least 1")
    @Max(value = 1440, message = "Escalation hours must be at most 1440")
    @Schema(description = "Hours after which an item still waiting is escalated; at least the target", example = "48")
    private Integer escalationHours;
}
