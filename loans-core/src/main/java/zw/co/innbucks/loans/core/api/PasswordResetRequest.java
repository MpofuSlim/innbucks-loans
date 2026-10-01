package zw.co.innbucks.loans.core.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.innbucks.loans.core.notifications.NotificationChannel;

/**
 * An administrator's reset of another user's password. A fresh temporary password is generated
 * server-side and delivered to the user over {@code channel}; it is never returned in the response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetRequest {
    @Schema(description = "WHATSAPP (the default: WhatsApp, and SMS when WhatsApp fails), SMS or EMAIL",
            example = "WHATSAPP")
    private NotificationChannel channel;
}
