package zw.co.reikan.loans.core.api;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.loans.core.notifications.NotificationChannel;

/**
 * Super-admin request to reset a user's password. A fresh temporary password is
 * generated server-side and delivered to the user over {@code channel} (EMAIL,
 * SMS or WHATSAPP); it is never returned in the response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminResetPasswordRequest {
    @NotBlank(message = "Username is required")
    private String username;
    @NotNull(message = "Delivery channel is required (EMAIL, SMS or WHATSAPP)")
    private NotificationChannel channel;
}
