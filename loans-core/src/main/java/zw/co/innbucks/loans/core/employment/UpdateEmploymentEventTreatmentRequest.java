package zw.co.innbucks.loans.core.employment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A new treatment for one type of employment event (FR-SSB-024). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEmploymentEventTreatmentRequest {

    @NotNull(message = "Application treatment is required")
    @Schema(description = "For applications not yet paid out: CONTINUE, HOLD or DECLINE", example = "HOLD")
    private ApplicationTreatment applicationTreatment;

    @NotNull(message = "Loan treatment is required")
    @Schema(description = "For loans already paid out: NONE or REVIEW", example = "REVIEW")
    private LoanTreatment loanTreatment;

    @NotNull(message = "Notify on decline is required")
    @Schema(description = "Whether a declined applicant is sent the decline SMS", example = "true")
    private Boolean notifyOnDecline;
}
