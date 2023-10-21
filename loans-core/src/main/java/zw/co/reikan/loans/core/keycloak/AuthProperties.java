package zw.co.reikan.loans.core.keycloak;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "bulkit")
public class AuthProperties {
    private String clientId;
    private String clientSecret;
    private String username;
    private String password;
    private String authUrl;
}
