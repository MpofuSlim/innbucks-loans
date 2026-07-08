package zw.co.reikan.loans.core.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.loans.core.notifications.NotificationChannel;

/**
 * Super-admin request to reset a user's password. A fresh temporary password is
 * generated server-side and delivered to the user over {@code channel} (EMAIL or
 * SMS); it is never returned in the response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminResetPasswordRequest {
    private String username;
    private NotificationChannel channel;
}
