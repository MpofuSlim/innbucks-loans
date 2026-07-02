package zw.co.reikan.loans.core.channelsecurity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 request signing (AWS SigV4-style canonicalisation).
 *
 * <p>Canonical string signed by every channel gateway (Node.js, React BFF):</p>
 * <pre>
 *   channelId + "\n" + timestampMillis + "\n" + nonce + "\n" + SHA256_HEX(rawBody)
 * </pre>
 *
 * <p>Hashing the raw body (rather than concatenating it) keeps the canonical
 * string printable and length-bounded while still binding the signature to
 * every byte of the payload. The nonce + timestamp bind the signature to a
 * single moment — combined with {@link ReplayNonceCache} this defeats replay
 * attacks even inside the clock-skew window.</p>
 *
 * <p>Comparison is constant-time ({@link MessageDigest#isEqual}) to prevent
 * timing side-channels.</p>
 */
@Slf4j
@Component
public class HmacSignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";

    public String canonicalString(String channelId, String timestamp, String nonce, byte[] rawBody) {
        return channelId + "\n" + timestamp + "\n" + nonce + "\n" + sha256Hex(rawBody);
    }

    public String sign(String secret, String channelId, String timestamp, String nonce, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(canonicalString(channelId, timestamp, nonce, rawBody)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time verification of a presented signature. */
    public boolean verify(String secret, String channelId, String timestamp, String nonce,
                          byte[] rawBody, String presentedSignatureHex) {
        if (presentedSignatureHex == null || presentedSignatureHex.isBlank()) {
            return false;
        }
        String expected = sign(secret, channelId, timestamp, nonce, rawBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presentedSignatureHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload == null ? new byte[0] : payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
