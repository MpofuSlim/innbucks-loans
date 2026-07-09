package zw.co.reikan.loans.core.api;
import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class ForgotPasswordRequest {
    @NotBlank(message = "Username is required")
    private String username;
}
