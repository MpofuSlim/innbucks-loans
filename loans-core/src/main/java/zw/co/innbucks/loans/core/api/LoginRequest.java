package zw.co.innbucks.loans.core.api;
import jakarta.validation.constraints.NotBlank;

import lombok.*;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class LoginRequest {
    @NotBlank(message = "Username is required")
    private String username;
    @ToString.Exclude
    @NotBlank(message = "Password is required")
    private String password;
}
