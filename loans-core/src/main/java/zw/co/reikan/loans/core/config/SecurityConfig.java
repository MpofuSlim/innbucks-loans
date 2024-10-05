package zw.co.reikan.loans.core.config;

import lombok.extern.slf4j.Slf4j;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import zw.co.reikan.loans.core.keycloak.AuthProperties;

@Configuration
@Slf4j
public class SecurityConfig {

    public static final String MASTER_REALM = "master";
    public static final String ADMIN_CLI = "admin-cli";
    private static final String GROUPS = "groups";
    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_CLAIM = "roles";

    @Bean
    Keycloak keycloak(AuthProperties params) {

        log.info("Creating keyclok: username: {}, password: {}", params.getUsername(), params.getPassword() );
        return KeycloakBuilder.builder()
                .serverUrl(params.getAuthUrl())
                .realm(MASTER_REALM)
                .clientId(ADMIN_CLI)
                .grantType(OAuth2Constants.PASSWORD)
                .username(params.getUsername())
                .password(params.getPassword())
                .build();
    }


//    @Bean
//    public Keycloak keycloak(AuthProperties params) {
//        Keycloak keycloak = KeycloakBuilder. builder()
//                .serverUrl("https:// sso. example. com/ auth")
//                .realm("realm")
//                .username("user")
//                .password("pass")
//                .clientId("client")
//                .clientSecret("secret")
//                .resteasyClient(new ResteasyClientBuilder().connectionPoolSize(20).build())
//                .build();
//    }

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
