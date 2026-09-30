package zw.co.innbucks.loans.core.employment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** An employment event to record against a borrower's EC number (FR-SSB-024). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordEmploymentEventRequest {

    @NotBlank(message = "EC number is required")
    @Schema(description = "The borrower's EC number", example = "1234567A")
    private String ecNumber;

    @NotNull(message = "Event type is required")
    @Schema(description = "What happened: TRANSFER, SECONDMENT, PROMOTION, SUSPENSION, UNPAID_LEAVE, RESIGNATION,"
            + " RETIREMENT or DEATH_IN_SERVICE", example = "SUSPENSION")
    private EmploymentEventType eventType;

    @NotNull(message = "Effective date is required")
    @Schema(description = "When it took or takes effect", example = "2026-10-01")
    private LocalDate effectiveDate;

    @Schema(description = "When a secondment, suspension or unpaid leave ends, if known; refused for other events",
            example = "2026-12-31")
    private LocalDate endDate;

    @Size(max = 255, message = "Ministry must be at most 255 characters")
    @Schema(description = "The ministry moved to; required for a TRANSFER or SECONDMENT",
            example = "Ministry of Health and Child Care")
    private String ministry;

    @Size(max = 255, message = "Station must be at most 255 characters")
    @Schema(description = "The station moved to, for a TRANSFER or SECONDMENT", example = "Mpilo Central Hospital")
    private String station;

    @Size(max = 64, message = "Grade must be at most 64 characters")
    @Schema(description = "The new grade or notch; required for a PROMOTION", example = "D3")
    private String grade;

    @Size(max = 500, message = "Note must be at most 500 characters")
    @Schema(description = "Where the event was reported from, or anything an officer should know",
            example = "Suspension letter from the Public Service Commission, ref PSC/2026/0912")
    private String note;
}
