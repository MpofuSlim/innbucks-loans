package zw.co.reikan.loans.core.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserGroup;
import zw.co.reikan.loans.core.user.UserRepository;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The committed application.yml default secret is public, and roles are read
 * straight from token claims — so a deployment running on it hands anyone a
 * SUPER_ADMIN token. It must refuse to boot unless a dev/test/local/it profile
 * is active, and the decoder must only accept tokens carrying our own issuer.
 */
class JwtConfigTest {

    private final UserRepository users = mock(UserRepository.class);
    private final TokenVersionValidator versions = new TokenVersionValidator(users);

    {
        when(users.findTokenVersionByUsername(anyString())).thenReturn(Optional.of(0L));
    }

    /** Exactly the default in loans-api application.yml. */
    private static final String COMMITTED_DEFAULT = "innbucks-loans-local-dev-secret-change-me-please";

    @Test
    @DisplayName("the committed default secret with NO active profile → refuses to boot")
    void placeholderRefusedWithNoProfiles() {
        assertThatThrownBy(() -> new JwtConfig(properties(COMMITTED_DEFAULT), environment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret is a development placeholder")
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("the committed default secret under {api} → refuses to boot")
    void placeholderRefusedUnderApiProfile() {
        assertThatThrownBy(() -> new JwtConfig(properties(COMMITTED_DEFAULT), environment("api")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[api]");
    }

    @Test
    @DisplayName("a deployed QA box's test-environment profile is NOT the test profile → still refused")
    void testEnvironmentProfileDoesNotOptOut() {
        assertThatThrownBy(() -> new JwtConfig(properties(COMMITTED_DEFAULT), environment("api", "test-environment")))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "local", "it"})
    @DisplayName("the committed default secret under a dev/test/local/it profile → boots, so local work still does")
    void placeholderAllowedUnderNonDeploymentProfile(String profile) {
        assertThatCode(() -> new JwtConfig(properties(COMMITTED_DEFAULT), environment("api", profile)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CHANGE-ME-0123456789abcdefghijklmnopqrstuvwxyz",
            "our-prod-change_me-0123456789abcdefghijklmnopq",
            "Dev-Secret-0123456789abcdefghijklmnopqrstuvwxy",
            "placeholder-0123456789abcdefghijklmnopqrstuvwx",
            "example-jwt-secret-0123456789abcdefghijklmnopq",
            "changeit-0123456789abcdefghijklmnopqrstuvwxyz0"})
    @DisplayName("every placeholder marker is caught, case-insensitively")
    void everyMarkerIsCaught(String secret) {
        assertThatThrownBy(() -> new JwtConfig(properties(secret), environment("api")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a real 48-byte random secret under {api} → boots")
    void realRandomSecretAccepted() {
        assertThatCode(() -> new JwtConfig(properties(randomSecret()), environment("api")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a short secret is still refused, whatever the profile")
    void shortSecretStillRefused() {
        assertThatThrownBy(() -> new JwtConfig(properties("too-short"), environment("dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("a token minted by JwtService carries jwt.issuer and is accepted")
    void tokenFromJwtServiceIsAccepted() {
        JwtProperties properties = properties(randomSecret());
        JwtConfig config = new JwtConfig(properties, environment("api"));
        JwtService jwtService = new JwtService(config.jwtEncoder(), properties);

        User admin = new User();
        admin.setUsername("admin");
        admin.setExternalSystemId("7f1c2a9e-0000-4000-8000-000000000001");
        admin.setGroups(Set.of(UserGroup.SUPER_ADMIN));

        Jwt jwt = config.jwtDecoder(versions).decode(jwtService.generateToken(admin));

        assertThat(jwt.getClaimAsString("iss")).isEqualTo("innbucks-loans");
        assertThat(jwt.getSubject()).isEqualTo("7f1c2a9e-0000-4000-8000-000000000001");
        assertThat(jwt.getClaims().get("token_version")).isEqualTo(0L);
    }

    @Test
    @DisplayName("a password change ends every token minted before it; one minted after it is accepted")
    void passwordChangeEndsEarlierTokens() {
        JwtProperties properties = properties(randomSecret());
        JwtConfig config = new JwtConfig(properties, environment("api"));
        JwtService jwtService = new JwtService(config.jwtEncoder(), properties);
        User admin = new User();
        admin.setUsername("admin");
        admin.setExternalSystemId("7f1c2a9e-0000-4000-8000-000000000001");
        admin.setGroups(Set.of(UserGroup.SUPER_ADMIN));
        String before = jwtService.generateToken(admin);

        admin.bumpTokenVersion();
        when(users.findTokenVersionByUsername("admin")).thenReturn(Optional.of(1L));
        String after = jwtService.generateToken(admin);

        JwtDecoder decoder = config.jwtDecoder(versions);
        assertThatThrownBy(() -> decoder.decode(before))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("password was last changed");
        assertThat(decoder.decode(after).getClaims().get("token_version")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a token from before token_version existed is good until its user's first password change")
    void legacyTokenWithoutTheClaim() {
        JwtConfig config = new JwtConfig(properties(randomSecret()), environment("api"));
        String legacy = mint(config.jwtEncoder(), "innbucks-loans");
        JwtDecoder decoder = config.jwtDecoder(versions);

        when(users.findTokenVersionByUsername("admin")).thenReturn(Optional.of(0L));
        assertThat(decoder.decode(legacy).getClaimAsString("preferred_username")).isEqualTo("admin");

        when(users.findTokenVersionByUsername("admin")).thenReturn(Optional.of(1L));
        assertThatThrownBy(() -> decoder.decode(legacy)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("a token whose user no longer exists is refused")
    void deletedUsersTokenRefused() {
        JwtConfig config = new JwtConfig(properties(randomSecret()), environment("api"));
        String token = mint(config.jwtEncoder(), "innbucks-loans");
        when(users.findTokenVersionByUsername("admin")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> config.jwtDecoder(versions).decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("no longer exists");
    }

    @Test
    @DisplayName("a correctly signed token with a different iss → rejected")
    void foreignIssuerRejected() {
        JwtConfig config = new JwtConfig(properties(randomSecret()), environment("api"));
        String token = mint(config.jwtEncoder(), "someone-else");

        JwtDecoder decoder = config.jwtDecoder(versions);
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss");
    }

    @Test
    @DisplayName("a correctly signed token with no iss at all → rejected")
    void missingIssuerRejected() {
        JwtConfig config = new JwtConfig(properties(randomSecret()), environment("api"));
        String token = mint(config.jwtEncoder(), null);

        JwtDecoder decoder = config.jwtDecoder(versions);
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    private static String mint(JwtEncoder encoder, String issuer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .subject("attacker")
                .claim("preferred_username", "admin")
                .claim("realm_access", Map.of("roles", List.of("SUPER_ADMIN")));
        if (issuer != null) {
            claims.issuer(issuer);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static JwtProperties properties(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        return properties;
    }

    private static MockEnvironment environment(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return environment;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
