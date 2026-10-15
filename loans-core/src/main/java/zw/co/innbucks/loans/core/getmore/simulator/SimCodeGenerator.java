package zw.co.innbucks.loans.core.getmore.simulator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.getmore.CouponFormat;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * The simulated store's random characters, from {@link SecureRandom} in Magento's alphabets
 * ({@code SalesRule/etc/di.xml}): {@code A-Z0-9} for alphanum, {@code A-Z} for alpha, {@code 0-9} for num. A simulated
 * code is a real secret on staging (it spends a test voucher), so it is never a predictable sequence.
 */
@Component
@ConditionalOnProperty(prefix = "loans.getmore", name = "mode", havingValue = "SIMULATED")
public class SimCodeGenerator {

    static final String ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static final String NUM = "0123456789";

    private final SecureRandom random = new SecureRandom();

    /** {@code length} random characters of {@code format}. */
    public String random(int length, CouponFormat format) {
        Objects.requireNonNull(format, "format");
        if (length < 1) {
            throw new IllegalArgumentException("A code needs at least one random character");
        }
        String alphabet = switch (format) {
            case ALPHANUM -> ALPHANUM;
            case ALPHA -> ALPHA;
            case NUM -> NUM;
        };
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = alphabet.charAt(random.nextInt(alphabet.length()));
        }
        return new String(out);
    }

    /** {@code prefix} (none when null) followed by {@code length} random characters of {@code format}. */
    public String code(String prefix, int length, CouponFormat format) {
        return (prefix == null ? "" : prefix) + random(length, format);
    }
}
