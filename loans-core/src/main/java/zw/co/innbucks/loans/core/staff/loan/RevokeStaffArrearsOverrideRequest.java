package zw.co.innbucks.loans.core.staff.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Ends an approved arrears override before it is used, so the written-off balance stops new loans again. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevokeStaffArrearsOverrideRequest {

    @NotBlank(message = "Reason is required")
    @Size(max = 255, message = "Reason must be at most 255 characters")
    @Schema(example = "Approved in error: the balance is not yet settled")
    private String reason;
}
