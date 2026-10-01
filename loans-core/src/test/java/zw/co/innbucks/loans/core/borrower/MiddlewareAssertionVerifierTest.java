package zw.co.innbucks.loans.core.borrower;

import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.MIDDLEWARE;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.NOW;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.PREVIOUS_EC;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.STRANGER;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.ZW;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.claims;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.es256;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.hs256;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.oneLine;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.properties;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.publicPem;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.rs256;

/**
 * The middleware's assertion is the one thing standing between a phone number and a staff member's loan, so every
 * check has a case: only an asymmetric signature by a configured key, the issuer and audience, the required claims, and
 * a bounded, current lifetime.
 */
class MiddlewareAssertionVerifierTest {

    private final MiddlewareAssertionVerifier verifier = verifier(publicPem(MIDDLEWARE), "");

    private static MiddlewareAssertionVerifier verifier(String publicKey, String previousPublicKey) {
        BorrowerProperties properties = properties(publicKey, previousPublicKey);
        return new MiddlewareAssertionVerifier(properties, ZW, new TestAssertionSigner(properties, ZW));
    }

    @Test
    @DisplayName("the middleware's genuine assertion proves the phone, how they signed in, and when")
    void genuineAssertion() {
        VerifiedAssertion verified = verifier.verify(rs256(MIDDLEWARE, claims().subject(" +263773456789 ").build()));

        assertThat(verified.phone()).isEqualTo("+263773456789");
        assertThat(verified.jti()).isEqualTo("mw-7c1d9e2a");
        assertThat(verified.methods()).containsExactly("pin");
        assertThat(verified.issuedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(240));
    }

    @Test
    @DisplayName("keys arrive however an env file holds them: PEM on one line with \\n, or the bare base64 body")
    void keyFormats() {
        String bare = publicPem(MIDDLEWARE).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        for (String key : List.of(oneLine(publicPem(MIDDLEWARE)), bare)) {
            assertThat(verifier(key, "").verify(rs256(MIDDLEWARE, claims().build())).phone())
                    .isEqualTo("+263773456789");
        }
        assertThatThrownBy(() -> verifier("not a key", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BORROWER_ASSERTION_PUBLIC_KEY");
    }

    @Test
    @DisplayName("during a rotation the previous key (here EC, ES256) is accepted beside the new one")
    void previousKey() {
        MiddlewareAssertionVerifier rotating = verifier(publicPem(MIDDLEWARE), publicPem(PREVIOUS_EC));

        assertThat(rotating.verify(es256(PREVIOUS_EC, claims().build())).phone()).isEqualTo("+263773456789");
        assertThat(rotating.verify(rs256(MIDDLEWARE, claims().build())).phone()).isEqualTo("+263773456789");
        assertThatThrownBy(() -> verifier.verify(es256(PREVIOUS_EC, claims().build())))
                .isInstanceOf(AssertionRejectedException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("an assertion signed by any other key is refused")
    void strangersKey() {
        assertThatThrownBy(() -> verifier.verify(rs256(STRANGER, claims().build())))
                .isInstanceOf(AssertionRejectedException.class)
                .hasMessageContaining("signature not made by a configured key");
    }

    @Test
    @DisplayName("a shared-secret (HS256) or unsigned assertion never reaches a key")
    void symmetricAndUnsignedRefused() {
        byte[] secret = "a-shared-secret-of-at-least-32-bytes!!".getBytes();
        assertThatThrownBy(() -> verifier.verify(hs256(secret, claims().build())))
                .isInstanceOf(AssertionRejectedException.class)
                .hasMessageContaining("algorithm not allowed: HS256");
        String unsigned = new PlainJWT(new PlainHeader(), claims().build()).serialize();
        assertThatThrownBy(() -> verifier.verify(unsigned))
                .isInstanceOf(AssertionRejectedException.class)
                .hasMessageContaining("not a signed JWT");
        assertThatThrownBy(() -> verifier.verify("  "))
                .isInstanceOf(AssertionRejectedException.class);
        assertThatThrownBy(() -> verifier.verify("garbage"))
                .isInstanceOf(AssertionRejectedException.class)
                .hasMessageContaining("not a signed JWT");
    }

    @Test
    @DisplayName("an assertion the middleware signed for anyone else (a fleet login, another issuer) is refused")
    void issuerAndAudience() {
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims().issuer("someone-else").build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("wrong issuer");
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims().audience("innbucks-foundry").build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("wrong audience");
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims().audience((String) null).build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("wrong audience");
        assertThat(verifier.verify(rs256(MIDDLEWARE,
                claims().audience(List.of("innbucks-foundry", "innbucks-lending")).build())).phone())
                .isEqualTo("+263773456789");
    }

    @Test
    @DisplayName("the phone, the jti, iat and exp are all required, and a jti fits its column")
    void requiredClaims() {
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims().subject(null).build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("no subject");
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims().jwtID(null).build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("jti");
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims().jwtID("j".repeat(129)).build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("jti");
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims().issueTime(null).build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("iat and exp");
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims().expirationTime(null).build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("iat and exp");
    }

    @Test
    @DisplayName("a lifetime over five minutes, or none, is refused")
    void lifetime() {
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims()
                .issueTime(at(-10)).expirationTime(at(291)).build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("lifetime 301s");
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims()
                .issueTime(at(0)).expirationTime(at(0)).build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("lifetime 0s");
        assertThat(verifier.verify(rs256(MIDDLEWARE, claims().issueTime(at(-10)).expirationTime(at(290)).build())))
                .isNotNull();
    }

    @Test
    @DisplayName("an expired assertion, or one from the future, is refused beyond 30 seconds of clock skew")
    void currency() {
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims()
                .issueTime(at(-300)).expirationTime(at(-31)).build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("expired");
        assertThat(verifier.verify(rs256(MIDDLEWARE, claims().issueTime(at(-300)).expirationTime(at(-29)).build())))
                .isNotNull();
        assertThatThrownBy(() -> verifier.verify(rs256(MIDDLEWARE, claims()
                .issueTime(at(31)).expirationTime(at(200)).build())))
                .isInstanceOf(AssertionRejectedException.class).hasMessageContaining("future");
        assertThat(verifier.verify(rs256(MIDDLEWARE, claims().issueTime(at(29)).expirationTime(at(200)).build())))
                .isNotNull();
    }

    @Test
    @DisplayName("with no key configured nothing is accepted, and the verifier says it is not configured")
    void unconfigured() {
        MiddlewareAssertionVerifier off = verifier("", "");

        assertThat(off.isConfigured()).isFalse();
        assertThatThrownBy(() -> off.verify(rs256(MIDDLEWARE, claims().build())))
                .isInstanceOf(AssertionRejectedException.class);
        assertThat(verifier.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("an amr that is not a list of strings counts as no method at all")
    void oddAmr() {
        JWTClaimsSet odd = claims().claim("amr", "pin").build();
        assertThat(verifier.verify(rs256(MIDDLEWARE, odd)).methods()).isEmpty();
        assertThat(verifier.verify(rs256(MIDDLEWARE, claims().claim("amr", null).build())).methods()).isEmpty();
    }

    @Test
    @DisplayName("a fresh step-up is a PIN or biometric from the last two minutes; anything else is not")
    void freshStepUp() {
        assertThat(verifier.isFreshStepUp(assertion(-120, "pin"))).isTrue();
        assertThat(verifier.isFreshStepUp(assertion(-5, "fpt"))).isTrue();
        assertThat(verifier.isFreshStepUp(assertion(-5, "face", "otp"))).isTrue();
        assertThat(verifier.isFreshStepUp(assertion(-121, "pin"))).isFalse();
        assertThat(verifier.isFreshStepUp(assertion(-5, "otp"))).isFalse();
        assertThat(verifier.isFreshStepUp(assertion(-5))).isFalse();
    }

    private static VerifiedAssertion assertion(long issuedSecondsFromNow, String... methods) {
        Instant issued = NOW.plusSeconds(issuedSecondsFromNow);
        return new VerifiedAssertion("+263773456789", issued, issued.plusSeconds(300), "mw-1", List.of(methods));
    }

    private static Date at(long secondsFromNow) {
        return Date.from(NOW.plusSeconds(secondsFromNow));
    }
}
