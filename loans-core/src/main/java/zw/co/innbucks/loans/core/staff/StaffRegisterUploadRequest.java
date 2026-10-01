package zw.co.innbucks.loans.core.staff;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** A staff register file to load, for a second person to approve (FR-SGL-002). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffRegisterUploadRequest {

    @NotBlank(message = "File name is required")
    @Size(max = 255, message = "File name must be at most 255 characters")
    @Schema(example = "staff-register-2026-10.csv")
    private String fileName;

    @ToString.Exclude
    @NotBlank(message = "File content is required")
    @Schema(description = "The CSV file, base64-encoded: a header row, then one staff member per row; at most 2 MB and"
            + " 5000 rows", example = "RW1wbG95ZWUgTnVtYmVyLEZ1bGwgTmFtZSwuLi4=")
    private String content;

    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(description = "For the checker", example = "October register from Human Capital")
    private String comment;
}
