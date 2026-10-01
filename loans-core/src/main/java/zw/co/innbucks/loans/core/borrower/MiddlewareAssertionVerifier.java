package zw.co.innbucks.loans.core.borrower;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.config.MarketTimeZone;

import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Verifies the InnBucks middleware's assertion that a phone's owner has just authenticated (their PIN, or biometrics on
 * the phone), the proof a borrower signs in and approves a loan with. The same contract the ticketing fleet's
 * {@code /auth/exchange} verifies, with loans' own audience, so the middleware signs one shape for everyone:
 * <ul>
 *   <li>an asymmetric signature only (RS256/384/512, ES256/384/512), so a token signed with a shared secret, or with
 *       none, never reaches a key; loans holds only the PUBLIC key, so nothing here can mint one;</li>
 *   <li>{@code iss} and {@code aud} must match, so an assertion minted for another relying party is not a loan login;</li>
 *   <li>{@code sub} (the phone), {@code jti}, {@code iat} and {@code exp} are all required, the lifetime is bounded by
 *       {@code max-ttl-seconds}, and the clock skew allowed is 30 seconds;</li>
 *   <li>a previous key is accepted beside the current one, so a rotation has an overlap instead of a cliff.</li>
 * </ul>
 * One use per assertion is enforced by {@link AssertionUses}, not here.
 */
@Slf4j
@Component
public class MiddlewareAssertionVerifier {

    private static final Set<JWSAlgorithm> ALLOWED = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384,
            JWSAlgorithm.RS512, JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512);
    private static final Duration SKEW = Duration.ofSeconds(30);

    private final BorrowerProperties properties;
    private final MarketTimeZone marketTimeZone;
    private final List<PublicKey> keys = new ArrayList<>();

    public MiddlewareAssertionVerifier(BorrowerProperties properties, MarketTimeZone marketTimeZone,
                                       TestAssertionSigner testSigner) {
        this.properties = properties;
        this.marketTimeZone = marketTimeZone;
        BorrowerProperties.Assertion assertion = properties.getAssertion();
        if (StringUtils.isNotBlank(assertion.getPublicKey())) {
            keys.add(PemKeys.publicKey(assertion.getPublicKey(), "BORROWER_ASSERTION_PUBLIC_KEY"));
        }
        if (StringUtils.isNotBlank(assertion.getPreviousPublicKey())) {
            keys.add(PemKeys.publicKey(assertion.getPreviousPublicKey(), "BORROWER_ASSERTION_PREVIOUS_PUBLIC_KEY"));
        }
        testSigner.publicKey().ifPresent(keys::add);
        if (keys.isEmpty()) {
            log.warn("Borrower sign-in is OFF: no middleware public key (BORROWER_ASSERTION_PUBLIC_KEY), so no SuperApp"
                    + " borrower can sign in to the Staff Grocery Loan");
        }
    }

    /** Whether any assertion can be verified at all. */
    public boolean isConfigured() {
        return !keys.isEmpty();
    }

    /**
     * Verifies the assertion and returns what it proves.
     *
     * @throws AssertionRejectedException for any failure; the reason is for the log, never the caller
     */
    public VerifiedAssertion verify(String compact) {
        if (StringUtils.isBlank(compact)) {
            throw new AssertionRejectedException("empty assertion");
        }
        if (!isConfigured()) {
            throw new AssertionRejectedException("no verification key configured");
        }
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(compact.strip());
            claims = jwt.getJWTClaimsSet();
        } catch (Exception e) {
            throw new AssertionRejectedException("not a signed JWT");
        }
        JWSAlgorithm algorithm = jwt.getHeader().getAlgorithm();
        if (!ALLOWED.contains(algorithm)) {
            throw new AssertionRejectedException("algorithm not allowed: " + algorithm);
        }
        if (!signedByAKnownKey(jwt)) {
            throw new AssertionRejectedException("signature not made by a configured key");
        }
        BorrowerProperties.Assertion expected = properties.getAssertion();
        if (!expected.getIssuer().equals(claims.getIssuer())) {
            throw new AssertionRejectedException("wrong issuer: " + claims.getIssuer());
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(expected.getAudience())) {
            throw new AssertionRejectedException("wrong audience: " + claims.getAudience());
        }
        if (StringUtils.isBlank(claims.getSubject())) {
            throw new AssertionRejectedException("no subject");
        }
        if (StringUtils.isBlank(claims.getJWTID()) || claims.getJWTID().length() > 128) {
            throw new AssertionRejectedException("no jti, or one over 128 characters");
        }
        if (claims.getIssueTime() == null || claims.getExpirationTime() == null) {
            throw new AssertionRejectedException("iat and exp are both required");
        }
        Instant issuedAt = claims.getIssueTime().toInstant();
        Instant expiresAt = claims.getExpirationTime().toInstant();
        Instant now = marketTimeZone.nowUtc().toInstant(ZoneOffset.UTC);
        long ttl = expiresAt.getEpochSecond() - issuedAt.getEpochSecond();
        if (ttl <= 0 || ttl > expected.getMaxTtlSeconds()) {
            throw new AssertionRejectedException("lifetime " + ttl + "s outside 1.." + expected.getMaxTtlSeconds());
        }
        if (issuedAt.isAfter(now.plus(SKEW))) {
            throw new AssertionRejectedException("issued in the future");
        }
        if (!expiresAt.isAfter(now.minus(SKEW))) {
            throw new AssertionRejectedException("expired");
        }
        return new VerifiedAssertion(claims.getSubject().strip(), issuedAt, expiresAt, claims.getJWTID(),
                methods(claims));
    }

    /**
     * Whether the assertion proves the borrower has just entered their PIN or used biometrics (FR-SGL-028): signed no
     * more than {@code step-up-max-age-seconds} ago, naming one of the {@code step-up-methods}.
     */
    public boolean isFreshStepUp(VerifiedAssertion assertion) {
        BorrowerProperties.Assertion expected = properties.getAssertion();
        Instant now = marketTimeZone.nowUtc().toInstant(ZoneOffset.UTC);
        return !assertion.issuedAt().isBefore(now.minusSeconds(expected.getStepUpMaxAgeSeconds()))
                && assertion.methods().stream().anyMatch(expected.getStepUpMethods()::contains);
    }

    private boolean signedByAKnownKey(SignedJWT jwt) {
        for (PublicKey key : keys) {
            try {
                JWSVerifier verifier = key instanceof RSAPublicKey rsa ? new RSASSAVerifier(rsa)
                        : new ECDSAVerifier((ECPublicKey) key);
                if (verifier.supportedJWSAlgorithms().contains(jwt.getHeader().getAlgorithm()) && jwt.verify(verifier)) {
                    return true;
                }
            } catch (Exception wrongKey) {
                // try the next key
            }
        }
        return false;
    }

    private static List<String> methods(JWTClaimsSet claims) {
        try {
            List<String> amr = claims.getStringListClaim("amr");
            return amr == null ? List.of() : List.copyOf(amr);
        } catch (Exception notAList) {
            return List.of();
        }
    }
}
