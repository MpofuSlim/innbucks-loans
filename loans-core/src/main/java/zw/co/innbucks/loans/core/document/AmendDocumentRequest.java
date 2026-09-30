package zw.co.innbucks.loans.core.document;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** A new version of a payslip or national ID, and why it replaces the one on file. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AmendDocumentRequest {

    /** Base64, raw or as a data URL: a PDF, PNG, JPEG or GIF. */
    @ToString.Exclude
    @NotBlank(message = "Content is required")
    @Schema(description = "The new file: base64, raw or as a data URL (PDF, PNG, JPEG or GIF)",
            example = "JVBERi0xLjcKJcfsj6IK...")
    private String content;

    @NotBlank(message = "Reason is required")
    @Size(max = 255, message = "Reason must be at most 255 characters")
    @Schema(example = "August payslip, as Credit asked; the June one was out of date")
    private String reason;
}
