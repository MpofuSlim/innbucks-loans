package zw.co.reikan.loans.core.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for self-issued HS256 access tokens. Replaces the former
 * Keycloak {@code bulkit.*} properties — the platform now mints and validates
 * its own JWTs with a shared symmetric secret.
 */
@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * HMAC signing secret. MUST be at least 32 bytes for HS256. Supply via the
     * {@code JWT_SECRET} environment variable in every deployed environment.
     */
    private String secret;

    /** Access-token lifetime in milliseconds. */
    private long expiration = 86_400_000L;

    /** Value placed in the {@code iss} claim and required on validation. */
    private String issuer = "innbucks-loans";
}
