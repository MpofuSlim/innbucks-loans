package zw.co.innbucks.loans.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

/** The signed-in user changing their own password. */
@Data
public class ChangePasswordRequest {
    @ToString.Exclude
    @NotBlank(message = "Current password is required")
    @Schema(example = "Temp#Pass42")
    private String currentPassword;
    @ToString.Exclude
    @NotBlank(message = "New password is required")
    @Schema(description = "8 to 72 characters, no leading or trailing space, different from the current one",
            example = "Kariba-Sunset-2026")
    private String newPassword;
}
