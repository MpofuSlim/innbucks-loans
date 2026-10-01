package zw.co.innbucks.loans.core.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How a code is kept without being stored as written (FR-SGL-040), and the keys' configuration rules. */
class VoucherCodeVaultTest {

    static final String HMAC_KEY = "u8Yc0m3l9G2pQ7vX1sR5tW4zN6bA0dE8fH3jK2LmPq0=";
    static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes());
    private static final String CODE = "4829150673318406";

    static VoucherCodeVault vault() {
        return vault(HMAC_KEY, ENCRYPTION_KEY, "test");
    }

    private static VoucherCodeVault vault(String hmac, String encryption, String... profiles) {
        VoucherProperties properties = new VoucherProperties();
        properties.setCodeHmacKey(hmac);
        properties.setCodeEncryptionKey(encryption);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new VoucherCodeVault(properties, environment);
    }

    @Test
    @DisplayName("the code is fingerprinted the same way every time, sealed differently every time, and unsealed")
    void roundTrip() {
        VoucherCodeVault vault = vault();
        assertThat(vault.isConfigured()).isTrue();
        assertThat(vault.fingerprint(CODE)).matches("[0-9a-f]{64}").isEqualTo(vault.fingerprint(CODE))
                .isNotEqualTo(vault.fingerprint("7391045288672151"));
        String sealed = vault.encrypt(CODE);
        assertThat(sealed).startsWith("v1:").doesNotContain(CODE).isNotEqualTo(vault.encrypt(CODE));
        assertThat(vault.decrypt(sealed)).isEqualTo(CODE);
    }

    @Test
    @DisplayName("the fingerprint depends on the key: a database copy alone cannot be checked against guessed codes")
    void fingerprintIsKeyed() {
        String other = vault("a-completely-different-hmac-key-of-enough-length", ENCRYPTION_KEY, "test")
                .fingerprint(CODE);
        assertThat(vault().fingerprint(CODE)).isNotEqualTo(other);
    }

    @Test
    @DisplayName("an altered or foreign sealed code is refused, not misread")
    void alteredCiphertextRefused() {
        VoucherCodeVault vault = vault();
        String sealed = vault.encrypt(CODE);
        byte[] packed = Base64.getDecoder().decode(sealed.substring(3));
        packed[packed.length - 1] ^= 1;
        String altered = "v1:" + Base64.getEncoder().encodeToString(packed);
        assertThatThrownBy(() -> vault.decrypt(altered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault.decrypt(CODE)).isInstanceOf(IllegalStateException.class);
        VoucherCodeVault otherKey = vault(HMAC_KEY, Base64.getEncoder().encodeToString(
                "fedcba9876543210fedcba9876543210".getBytes()), "test");
        assertThatThrownBy(() -> otherKey.decrypt(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("with neither key, vouchers are off: every use is a 503, and the service still starts")
    void offWithoutKeys() {
        VoucherCodeVault vault = vault("", "", "prod");
        assertThat(vault.isConfigured()).isFalse();
        assertThatThrownBy(() -> vault.fingerprint(CODE)).isInstanceOf(VouchersUnavailableException.class);
        assertThatThrownBy(() -> vault.encrypt(CODE)).isInstanceOf(VouchersUnavailableException.class);
        assertThatThrownBy(() -> vault.decrypt("v1:AAAA")).isInstanceOf(VouchersUnavailableException.class);
    }

    @Test
    @DisplayName("one key without the other, a short or malformed key, or equal keys refuse to start")
    void misconfigurationRefusesToStart() {
        assertThatThrownBy(() -> vault(HMAC_KEY, "", "test")).hasMessageContaining("VOUCHER_CODE_ENCRYPTION_KEY");
        assertThatThrownBy(() -> vault("", ENCRYPTION_KEY, "test")).hasMessageContaining("VOUCHER_CODE_HMAC_KEY");
        assertThatThrownBy(() -> vault("too-short", ENCRYPTION_KEY, "test")).hasMessageContaining("at least 32");
        assertThatThrownBy(() -> vault(HMAC_KEY, "not base64 !!", "test")).hasMessageContaining("base64");
        assertThatThrownBy(() -> vault(HMAC_KEY, Base64.getEncoder().encodeToString(new byte[16]), "test"))
                .hasMessageContaining("exactly 32 bytes");
        assertThatThrownBy(() -> vault(ENCRYPTION_KEY, ENCRYPTION_KEY, "test")).hasMessageContaining("must differ");
    }

    @Test
    @DisplayName("a published placeholder HMAC key refuses to start outside dev/test/local/it, profile-less included")
    void placeholderRefusedInDeployment() {
        String placeholder = "change-me-voucher-hmac-key-for-local-dev-only";
        assertThat(vault(placeholder, ENCRYPTION_KEY, "dev").isConfigured()).isTrue();
        assertThatThrownBy(() -> vault(placeholder, ENCRYPTION_KEY, "prod")).hasMessageContaining("placeholder");
        assertThatThrownBy(() -> vault(placeholder, ENCRYPTION_KEY)).hasMessageContaining("placeholder");
    }
}
