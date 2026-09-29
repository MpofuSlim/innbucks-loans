package zw.co.innbucks.loans.dto;
import jakarta.validation.constraints.NotBlank;

import lombok.Data;
import lombok.ToString;

@Data
public class ChangePasswordRequest {
    @ToString.Exclude
    @NotBlank(message = "Current password is required")
    private String currentPassword;
    @ToString.Exclude
    @NotBlank(message = "New password is required")
    private String newPassword;
}
