package zw.co.innbucks.loans.core.voucher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.auth.JwtConfig;
import zw.co.innbucks.loans.core.config.DeploymentProfiles;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * How a voucher code is kept without being stored as written (FR-SGL-040, FR-GEN-014). A code is worth its face value
 * to whoever holds it, so a copy of the database must not be a list of spendable codes:
 * <ul>
 *   <li>{@link #fingerprint} keys the code with HMAC-SHA256: the column a till's code is looked up by. A code is 15
 *       random digits and a check digit, about 10^15 possibilities, few enough that a bare hash could be reversed by
 *       trying them all; keyed, it cannot without the key.</li>
 *   <li>{@link #encrypt} seals it with AES-256-GCM, so it can be sent to the customer again and shown to staff entitled
 *       to see it. The stored form is {@code v1:} and base64 of the 12-byte nonce then the ciphertext and tag; the
 *       prefix leaves room for a second key.</li>
 * </ul>
 * Both keys come from the environment ({@code VOUCHER_CODE_HMAC_KEY}, {@code VOUCHER_CODE_ENCRYPTION_KEY}). With neither,
 * vouchers are off: every call that needs a key is a {@link VouchersUnavailableException}, and nothing else is affected.
 * With only one, a malformed one, or a published placeholder outside dev/test/local/it, the service refuses to start.
 */
@Slf4j
@Component
public class VoucherCodeVault {

    private static final String HMAC = "HmacSHA256";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String VERSION = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec hmacKey;
    private final SecretKeySpec encryptionKey;
    private final SecureRandom random = new SecureRandom();

    public VoucherCodeVault(VoucherProperties properties, Environment environment) {
        String hmac = trimmed(properties.getCodeHmacKey());
        String encryption = trimmed(properties.getCodeEncryptionKey());
        if (hmac.isEmpty() && encryption.isEmpty()) {
            log.warn("Vouchers are OFF: VOUCHER_CODE_HMAC_KEY and VOUCHER_CODE_ENCRYPTION_KEY are not set, so no voucher"
                    + " can be issued, shown in full or redeemed. Generate each with `openssl rand -base64 32`.");
            this.hmacKey = null;
            this.encryptionKey = null;
            return;
        }
        if (hmac.isEmpty() || encryption.isEmpty()) {
            throw new IllegalStateException("Set both VOUCHER_CODE_HMAC_KEY and VOUCHER_CODE_ENCRYPTION_KEY, or"
                    + " neither: " + (hmac.isEmpty() ? "VOUCHER_CODE_HMAC_KEY" : "VOUCHER_CODE_ENCRYPTION_KEY")
                    + " is missing");
        }
        byte[] hmacBytes = hmac.getBytes(StandardCharsets.UTF_8);
        if (hmacBytes.length < KEY_BYTES) {
            throw new IllegalStateException("VOUCHER_CODE_HMAC_KEY must be at least 32 bytes");
        }
        if (JwtConfig.isPlaceholder(hmac) && DeploymentProfiles.isDeployment(environment)) {
            throw new IllegalStateException("VOUCHER_CODE_HMAC_KEY is a development placeholder and no"
                    + " dev/test/local/it profile is active; set a random value (`openssl rand -base64 32`)");
        }
        byte[] encryptionBytes;
        try {
            encryptionBytes = Base64.getDecoder().decode(encryption);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("VOUCHER_CODE_ENCRYPTION_KEY must be base64 (`openssl rand -base64 32`)");
        }
        if (encryptionBytes.length != KEY_BYTES) {
            throw new IllegalStateException("VOUCHER_CODE_ENCRYPTION_KEY must be base64 of exactly 32 bytes, not "
                    + encryptionBytes.length);
        }
        if (Arrays.equals(hmacBytes, encryption.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("VOUCHER_CODE_HMAC_KEY and VOUCHER_CODE_ENCRYPTION_KEY must differ");
        }
        this.hmacKey = new SecretKeySpec(hmacBytes, HMAC);
        this.encryptionKey = new SecretKeySpec(encryptionBytes, "AES");
        log.info("Vouchers are on: voucher code keys configured");
    }

    /** Whether vouchers can be issued, read and redeemed. */
    public boolean isConfigured() {
        return hmacKey != null;
    }

    /** The code's lookup key: lowercase hex HMAC-SHA256 of its digits. */
    public String fingerprint(String code) {
        requireConfigured();
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** The code sealed for storage. */
    public String encrypt(String code) {
        requireConfigured();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(code.getBytes(StandardCharsets.US_ASCII));
            return VERSION + Base64.getEncoder().encodeToString(ByteBuffer.allocate(NONCE_BYTES + sealed.length)
                    .put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /**
     * The code a stored value seals.
     *
     * @throws IllegalStateException it was not sealed with this key, or has been altered
     */
    public String decrypt(String stored) {
        requireConfigured();
        if (stored == null || !stored.startsWith(VERSION)) {
            throw new IllegalStateException("Not a voucher code sealed by this service");
        }
        byte[] packed = Base64.getDecoder().decode(stored.substring(VERSION.length()));
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, packed, 0, NONCE_BYTES));
            return new String(cipher.doFinal(packed, NONCE_BYTES, packed.length - NONCE_BYTES),
                    StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The voucher code could not be unsealed: wrong key, or altered", e);
        }
    }

    void requireConfigured() {
        if (!isConfigured()) {
            throw new VouchersUnavailableException();
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.strip();
    }
}
