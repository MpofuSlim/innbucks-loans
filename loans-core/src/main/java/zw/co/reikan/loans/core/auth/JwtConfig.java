package zw.co.reikan.loans.core.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import zw.co.reikan.loans.core.config.DeploymentProfiles;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Wires the symmetric (HS256) {@link JwtEncoder} used to mint access tokens and
 * the {@link JwtDecoder} the resource server uses to validate them. Both are
 * derived from the same shared secret, so the platform is the issuer of every
 * token it accepts — no external identity provider is involved.
 */
@Configuration
public class JwtConfig {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Compared with separators folded away, so change-me, change_me and changeme all match. */
    private static final List<String> PLACEHOLDER_MARKERS =
            List.of("changeme", "devsecret", "placeholder", "example", "changeit");

    private final byte[] secretBytes;
    private final String issuer;

    public JwtConfig(JwtProperties jwtProperties, Environment environment) {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be configured with at least 32 bytes for HS256 signing");
        }
        // The application.yml default is published in this repo and roles come
        // straight from token claims, so anyone who has read it could mint a
        // BULKIT_ADMIN token. Only a dev/test/local/it process may run on it.
        if (isPlaceholder(secret) && DeploymentProfiles.isDeployment(environment)) {
            throw new IllegalStateException("jwt.secret is a development placeholder and no dev/test/local/it "
                    + "profile is active (active profiles: " + Arrays.toString(environment.getActiveProfiles())
                    + "). Set JWT_SECRET (or jwt.secret in /app/config) to a random value of at least "
                    + "32 bytes, e.g. `openssl rand -base64 48`.");
        }
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = jwtProperties.getIssuer();
    }

    static boolean isPlaceholder(String secret) {
        String folded = secret.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return PLACEHOLDER_MARKERS.stream().anyMatch(folded::contains);
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(secretBytes)
                .algorithm(JWSAlgorithm.HS256)
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        SecretKeySpec key = new SecretKeySpec(secretBytes, HMAC_ALGORITHM);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Accept only tokens stamped with our own iss (JwtService sets jwt.issuer),
        // on top of the default expiry and typ checks.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
