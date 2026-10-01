package zw.co.innbucks.loans.core.staff;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** The HR payroll master to reconcile the staff register against (FR-SGL-008). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffRegisterReconciliationRequest {

    @NotBlank(message = "File name is required")
    @Size(max = 255, message = "File name must be at most 255 characters")
    @Schema(example = "payroll-master-2026-10.csv")
    private String fileName;

    @ToString.Exclude
    @NotBlank(message = "File content is required")
    @Schema(description = "The payroll master as CSV, base64-encoded: a header row, then one employee per row; at most"
            + " 2 MB and 5000 rows. Only an employee number column is required",
            example = "RW1wbG95ZWUgTm8uLFN1cm5hbWUsRmlyc3QgTmFtZSxHcmFkZSxEZXBhcnRtZW50LFN0YXR1cyxQYXkgUG9pbnQK")
    private String content;

    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(example = "October payroll master from Human Capital")
    private String comment;
}
