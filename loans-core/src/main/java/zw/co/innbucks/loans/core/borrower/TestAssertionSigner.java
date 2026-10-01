package zw.co.innbucks.loans.core.borrower;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.config.MarketTimeZone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Staging's stand-in for the InnBucks middleware, until it signs assertions itself: signs one, in exactly the
 * middleware's shape, for any phone, to whoever presents the api key. So the SuperApp can be built against the real
 * flow (sign in with an assertion, approve with a fresh one) and switch to the middleware's with no change but the
 * source.
 *
 * <p>Off by default. On, whoever holds the api key can sign in, and borrow, as any staff member: it is announced at
 * ERROR on every boot, and must never be on where real money moves.
 */
@Slf4j
@Component
public class TestAssertionSigner {

    private final BorrowerProperties properties;
    private final MarketTimeZone marketTimeZone;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public TestAssertionSigner(BorrowerProperties properties, MarketTimeZone marketTimeZone) {
        this.properties = properties;
        this.marketTimeZone = marketTimeZone;
        BorrowerProperties.TestAssertions test = properties.getTestAssertions();
        if (!test.isEnabled()) {
            this.privateKey = null;
            this.publicKey = null;
            return;
        }
        if (StringUtils.length(test.getApiKey()) < 32 || StringUtils.isBlank(test.getPrivateKey())) {
            throw new IllegalStateException("loans.borrower.test-assertions is enabled but needs both an api key of at"
                    + " least 32 characters (BORROWER_TEST_ASSERTIONS_API_KEY) and a private key"
                    + " (BORROWER_TEST_ASSERTIONS_PRIVATE_KEY)");
        }
        this.privateKey = PemKeys.rsaPrivateKey(test.getPrivateKey(), "BORROWER_TEST_ASSERTIONS_PRIVATE_KEY");
        this.publicKey = PemKeys.publicHalf(privateKey);
        log.error("TEST ASSERTIONS ARE ON: POST /lending/v1/auth/test-assertions signs a borrower assertion for ANY phone"
                + " to whoever holds its api key, so they can sign in and borrow as any staff member. Staging only;"
                + " never where real money moves. Turn off with BORROWER_TEST_ASSERTIONS_ENABLED=false.");
    }

    public boolean isEnabled() {
        return privateKey != null;
    }

    /** Whether {@code presented} is the api key, compared in constant time. */
    public boolean acceptsKey(String presented) {
        return isEnabled() && presented != null && MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                properties.getTestAssertions().getApiKey().getBytes(StandardCharsets.UTF_8));
    }

    /** The key the verifier must also trust while this is on. */
    Optional<PublicKey> publicKey() {
        return Optional.ofNullable(publicKey);
    }

    /** An assertion for {@code phone}, authenticated by {@code methods}, signed now and good for two minutes. */
    public SignedAssertion sign(String phone, List<String> methods) {
        if (!isEnabled()) {
            throw new IllegalStateException("Test assertions are off");
        }
        LocalDateTime nowUtc = marketTimeZone.nowUtc().withNano(0);
        Instant now = nowUtc.toInstant(ZoneOffset.UTC);
        Instant expires = now.plusSeconds(120);
        BorrowerProperties.Assertion shape = properties.getAssertion();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(shape.getIssuer())
                .audience(shape.getAudience())
                .subject(phone)
                .jwtID("test-" + UUID.randomUUID())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expires))
                .claim("amr", methods)
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(privateKey));
            return new SignedAssertion(jwt.serialize(), nowUtc.plusSeconds(120));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a test assertion", e);
        }
    }

    /** A signed test assertion and when it expires (UTC; the API renders it at the market's offset). */
    public record SignedAssertion(String assertion, LocalDateTime expiresAt) {
        @Override
        public String toString() {
            return "SignedAssertion[expiresAt=" + expiresAt + "]";
        }
    }
}
