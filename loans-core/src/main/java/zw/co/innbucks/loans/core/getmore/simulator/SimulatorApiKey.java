package zw.co.innbucks.loans.core.getmore.simulator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * STAGING ONLY: the key the staging test actions ({@code /lending/v1/getmore-simulator/**}) are opened with,
 * {@code GETMORE_SIMULATOR_API_KEY}. Whoever holds it can spend, refund and expire every simulated voucher, so it is
 * compared in constant time and never logged or audited: an audit row names a key by {@link #fingerprint}, the first 8
 * hex characters of its SHA-256, which tells two keys apart and gives nothing away. Its strength and its difference
 * from every other secret are checked at boot ({@code GetMoreModeReport}). Exists only in SIMULATED.
 */
@Component
@ConditionalOnProperty(prefix = "loans.getmore", name = "mode", havingValue = "SIMULATED")
public class SimulatorApiKey {

    static final int FINGERPRINT_LENGTH = 8;

    private final byte[] key;

    @Autowired
    public SimulatorApiKey(GetMoreProperties properties) {
        this(properties.getSimulator().getApiKey());
    }

    /** @param apiKey the configured key; blank accepts nothing */
    public SimulatorApiKey(String apiKey) {
        this.key = (apiKey == null ? "" : apiKey.strip()).getBytes(StandardCharsets.UTF_8);
    }

    /** Whether {@code presented} (an {@code X-Api-Key} header, null when absent) is the key, in constant time. */
    public boolean accepts(String presented) {
        return key.length > 0 && presented != null
                && MessageDigest.isEqual(key, presented.getBytes(StandardCharsets.UTF_8));
    }

    /** The first 8 hex characters of the SHA-256 of {@code presented}: what the audit names a key by, never the key. */
    public static String fingerprint(String presented) {
        if (presented == null || presented.isEmpty()) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(presented.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    @Override
    public String toString() {
        return "SimulatorApiKey[configured=" + (key.length > 0) + "]";
    }
}
