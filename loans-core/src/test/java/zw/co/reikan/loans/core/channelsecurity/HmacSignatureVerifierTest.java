package zw.co.reikan.loans.core.channelsecurity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmacSignatureVerifierTest {

    private final HmacSignatureVerifier verifier = new HmacSignatureVerifier();

    private static final String SECRET = "test-secret";
    private static final String CHANNEL = "whatsapp_gateway";
    private static final String TS = "1767100000000";
    private static final String NONCE = "a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8";
    private static final byte[] BODY = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTrip_signThenVerify_succeeds() {
        String signature = verifier.sign(SECRET, CHANNEL, TS, NONCE, BODY);
        assertTrue(verifier.verify(SECRET, CHANNEL, TS, NONCE, BODY, signature));
    }

    @Test
    void verify_isCaseInsensitiveOnHex() {
        String signature = verifier.sign(SECRET, CHANNEL, TS, NONCE, BODY);
        assertTrue(verifier.verify(SECRET, CHANNEL, TS, NONCE, BODY, signature.toUpperCase()));
    }

    @Test
    void tamperedBody_failsVerification() {
        String signature = verifier.sign(SECRET, CHANNEL, TS, NONCE, BODY);
        byte[] tampered = "{\"amount\":10000}".getBytes(StandardCharsets.UTF_8); // 100x attack
        assertFalse(verifier.verify(SECRET, CHANNEL, TS, NONCE, tampered, signature));
    }

    @Test
    void differentNonceOrTimestamp_failsVerification() {
        String signature = verifier.sign(SECRET, CHANNEL, TS, NONCE, BODY);
        assertFalse(verifier.verify(SECRET, CHANNEL, TS, "ffffffffffffffffffffffffffffffff", BODY, signature));
        assertFalse(verifier.verify(SECRET, CHANNEL, "1767100000001", NONCE, BODY, signature));
    }

    @Test
    void wrongSecret_failsVerification() {
        String signature = verifier.sign(SECRET, CHANNEL, TS, NONCE, BODY);
        assertFalse(verifier.verify("other-secret", CHANNEL, TS, NONCE, BODY, signature));
    }

    @Test
    void missingSignature_failsClosed() {
        assertFalse(verifier.verify(SECRET, CHANNEL, TS, NONCE, BODY, null));
        assertFalse(verifier.verify(SECRET, CHANNEL, TS, NONCE, BODY, "  "));
    }

    @Test
    void canonicalString_matchesNodeSignerContract() {
        // Pinned so the Java side and docs/integration/channel-signing.js can
        // never drift apart silently: channelId \n ts \n nonce \n sha256Hex(body)
        String canonical = verifier.canonicalString(CHANNEL, TS, NONCE, BODY);
        assertEquals(CHANNEL + "\n" + TS + "\n" + NONCE + "\n"
                + HmacSignatureVerifier.sha256Hex(BODY), canonical);
    }

    @Test
    void nullBody_isSignedAsEmpty() {
        String withNull = verifier.sign(SECRET, CHANNEL, TS, NONCE, null);
        String withEmpty = verifier.sign(SECRET, CHANNEL, TS, NONCE, new byte[0]);
        assertEquals(withEmpty, withNull);
    }
}
