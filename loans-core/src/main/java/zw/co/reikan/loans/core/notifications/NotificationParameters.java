package zw.co.reikan.loans.core.notifications;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "notification")
public class NotificationParameters {
    private String username;
    private String password;
}
