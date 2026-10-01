package zw.co.innbucks.loans.core.staff;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A change to the grade-to-limit matrix, for a second person to approve (FR-SGL-010). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposeStaffGradeLimitRequest {

    @NotBlank(message = "Grade is required")
    @Size(max = 64, message = StaffGrades.MESSAGE)
    @Pattern(regexp = StaffGrades.PATTERN, message = StaffGrades.MESSAGE)
    @Schema(description = "The grade, as the bank grades its staff: a Paterson grade such as C4 or a band such as"
            + " CLERK/ASSISTANT/AGENT. Stored upper case, with runs of spaces as one and none around a slash",
            example = "C4")
    private String grade;

    @NotBlank(message = "Score band is required")
    @Size(max = 40, message = "Score band must be at most 40 characters")
    @Schema(description = "The internal credit score / risk band label the grade maps to", example = "Band C")
    private String scoreBand;

    @NotNull(message = "Maximum limit is required")
    @DecimalMin(value = "0.00", message = "Maximum limit cannot be negative")
    @Digits(integer = 17, fraction = 2, message = "Maximum limit must have at most 2 decimal places")
    @Schema(description = "The most a staff member of this grade may borrow, in the cell's currency; 0 stops lending"
            + " to the grade", example = "350.00")
    private BigDecimal maximumLimit;

    @NotNull(message = "Effective date is required")
    @Schema(description = "The market day from which the limit applies: today or later (yyyy-MM-dd)",
            example = "2026-11-01")
    private LocalDate effectiveFrom;

    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(description = "Why, for the checker", example = "Annual review approved by Credit and Human Capital")
    private String comment;
}
