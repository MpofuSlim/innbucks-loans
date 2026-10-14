package zw.co.innbucks.loans.core.getmore.live;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * RFC 3986 percent-encoding, as OAuth 1.0a (RFC 5849 §3.6) and Magento's verifier use it: every UTF-8 byte except
 * {@code A-Z a-z 0-9 - . _ ~} becomes an upper-case {@code %XX}. Never {@link java.net.URLEncoder}, which writes a
 * space as {@code +} and leaves {@code *} alone, so the base string we sign would not be the one Magento rebuilds.
 */
public final class Rfc3986 {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Rfc3986() {
    }

    public static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (isUnreserved(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return out.toString();
    }

    /**
     * Percent-decodes {@code value} as UTF-8. A {@code +} stays a {@code +}: in a URI it is not a space, and Magento
     * signs it as itself.
     *
     * @throws IllegalArgumentException on a malformed escape
     */
    public static String decode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        int i = 0;
        while (i < value.length()) {
            int c = value.codePointAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) {
                    throw new IllegalArgumentException("Malformed percent-escape in a query parameter");
                }
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("Malformed percent-escape in a query parameter");
                }
                out.write((hi << 4) | lo);
                i += 3;
            } else {
                byte[] bytes = new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
                i += Character.charCount(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
