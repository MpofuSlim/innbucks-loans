package zw.co.innbucks.loans.core.employment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An officer's resolution of a held application or a loan under review (FR-SSB-024). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResolveLoanEmploymentEventRequest {

    @NotNull(message = "Outcome is required")
    @Schema(description = "RELEASED or DECLINED for a held application, REVIEWED for a loan under review",
            example = "RELEASED")
    private LoanEmploymentEventOutcome outcome;

    @NotBlank(message = "Comment is required")
    @Size(max = 500, message = "Comment must be at most 500 characters")
    @Schema(description = "Why, or what will be done", example = "Suspension lifted on appeal; salary restored from October")
    private String comment;
}
