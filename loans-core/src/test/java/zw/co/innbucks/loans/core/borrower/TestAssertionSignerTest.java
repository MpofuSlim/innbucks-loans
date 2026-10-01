package zw.co.innbucks.loans.core.borrower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.MIDDLEWARE;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.TEST_API_KEY;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.ZW;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.properties;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.publicPem;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.withTestAssertions;

/**
 * Staging's stand-in for the middleware signs assertions the real verifier accepts, in the middleware's own shape, but
 * only when switched on, only properly configured, and only for the holder of its key.
 */
class TestAssertionSignerTest {

    @Test
    @DisplayName("off by default: signs nothing, trusts no key, accepts no api key")
    void offByDefault() {
        TestAssertionSigner signer = new TestAssertionSigner(new BorrowerProperties(), ZW);

        assertThat(signer.isEnabled()).isFalse();
        assertThat(signer.publicKey()).isEmpty();
        assertThat(signer.acceptsKey(TEST_API_KEY)).isFalse();
        assertThat(signer.acceptsKey("")).isFalse();
        assertThatThrownBy(() -> signer.sign("+263773456789", List.of("pin")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("switched on without a 32-character api key or a private key, it refuses to boot")
    void halfConfiguredRefusesToBoot() {
        BorrowerProperties shortKey = withTestAssertions(new BorrowerProperties());
        shortKey.getTestAssertions().setApiKey("too-short");
        assertThatThrownBy(() -> new TestAssertionSigner(shortKey, ZW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BORROWER_TEST_ASSERTIONS_API_KEY");

        BorrowerProperties noPrivateKey = withTestAssertions(new BorrowerProperties());
        noPrivateKey.getTestAssertions().setPrivateKey(" ");
        assertThatThrownBy(() -> new TestAssertionSigner(noPrivateKey, ZW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BORROWER_TEST_ASSERTIONS_PRIVATE_KEY");

        BorrowerProperties badPrivateKey = withTestAssertions(new BorrowerProperties());
        badPrivateKey.getTestAssertions().setPrivateKey(publicPem(MIDDLEWARE));
        assertThatThrownBy(() -> new TestAssertionSigner(badPrivateKey, ZW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }

    @Test
    @DisplayName("only the exact api key is accepted")
    void apiKey() {
        TestAssertionSigner signer = new TestAssertionSigner(withTestAssertions(new BorrowerProperties()), ZW);

        assertThat(signer.acceptsKey(TEST_API_KEY)).isTrue();
        assertThat(signer.acceptsKey(TEST_API_KEY + "x")).isFalse();
        assertThat(signer.acceptsKey(TEST_API_KEY.substring(1))).isFalse();
        assertThat(signer.acceptsKey(null)).isFalse();
    }

    @Test
    @DisplayName("what it signs passes the real verifier, beside the middleware's key, as the middleware's would")
    void signsWhatTheVerifierAccepts() {
        BorrowerProperties properties = withTestAssertions(properties(publicPem(MIDDLEWARE), ""));
        TestAssertionSigner signer = new TestAssertionSigner(properties, ZW);
        MiddlewareAssertionVerifier verifier = new MiddlewareAssertionVerifier(properties, ZW, signer);

        TestAssertionSigner.SignedAssertion signed = signer.sign("+263773456789", List.of("fpt"));
        VerifiedAssertion verified = verifier.verify(signed.assertion());

        assertThat(verified.phone()).isEqualTo("+263773456789");
        assertThat(verified.methods()).containsExactly("fpt");
        assertThat(verified.jti()).startsWith("test-");
        assertThat(verified.expiresAt()).isEqualTo(AssertionFixtures.NOW.plusSeconds(120));
        assertThat(signed.expiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 8, 2, 0));
        assertThat(verifier.isFreshStepUp(verified)).isTrue();
        assertThat(signed.toString()).doesNotContain(signed.assertion());
        assertThat(signer.sign("+263773456789", List.of("pin")).assertion()).isNotEqualTo(signed.assertion());
    }

    @Test
    @DisplayName("on its own, with no middleware key, it is what makes borrower sign-in available on staging")
    void aloneMakesSignInAvailable() {
        BorrowerProperties properties = withTestAssertions(new BorrowerProperties());
        TestAssertionSigner signer = new TestAssertionSigner(properties, ZW);

        assertThat(new MiddlewareAssertionVerifier(properties, ZW, signer).isConfigured()).isTrue();
    }
}
