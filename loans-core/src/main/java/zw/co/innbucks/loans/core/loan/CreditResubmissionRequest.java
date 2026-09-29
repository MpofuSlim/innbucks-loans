package zw.co.innbucks.loans.core.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The originator's answer to a loan Credit returned for more information. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreditResubmissionRequest {

    @NotBlank(message = "Comment is required")
    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(description = "The information Credit asked for",
            example = "Confirmed with the school bursar: the August payslip figures match the application")
    private String comment;
}
