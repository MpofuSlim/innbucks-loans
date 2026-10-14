package zw.co.innbucks.loans.core.getmore;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GetMore coupon codes: their typed form, the per-issuance prefix, and the ONE masked form ({@code ****8B1C}) every
 * log line, response, row and exception message uses.
 *
 * <p>A code looks like {@code INN-K7Q2M3XW-7KQ2M9XWD4RT8B1C}: the configured prefix ({@code INN-}, {@code SIM-} on a
 * simulating host), an 8-character random base32 nonce drawn when the issuance is written (so nobody can guess a
 * prefix and plant a coupon under it), a dash, and the store's random characters.
 */
public final class GetMoreCodes {

    /** RFC 4648 base32: letters and the digits 2 to 7, nothing that reads like another character. */
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int NONCE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern TYPED = Pattern.compile("^[A-Z0-9-]{8,64}$");
    /** Our own shape: {@code <prefix>-<base32 nonce>-<random>}. */
    private static final Pattern OUR_SHAPE =
            Pattern.compile("(?i)\\b[A-Z0-9]{2,8}-[A-Z2-7]{8}-[A-Z0-9]{8,32}\\b");
    /** A standalone run of 12 to 40 letters and digits: in GetMore's text, possibly a code without our prefix. */
    private static final Pattern BARE_RUN = Pattern.compile("(?i)(?<![A-Z0-9])[A-Z0-9]{12,40}(?![A-Z0-9])");
    private static final String REPORT_ID_PREFIX = "webapi-";

    private GetMoreCodes() {
    }

    /**
     * A code as someone typed or read it: trimmed, spaces removed, upper-cased; then 8 to 64 of {@code A-Z 0-9 -}.
     * Empty when it cannot be a GetMore code.
     */
    public static Optional<String> normalize(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String compact = typed.strip().replace(" ", "").toUpperCase(Locale.ROOT);
        return TYPED.matcher(compact).matches() ? Optional.of(compact) : Optional.empty();
    }

    /** 8 characters of RFC 4648 base32 from {@link SecureRandom}. */
    public static String newNonce() {
        char[] nonce = new char[NONCE_LENGTH];
        for (int i = 0; i < NONCE_LENGTH; i++) {
            nonce[i] = BASE32[RANDOM.nextInt(BASE32.length)];
        }
        return new String(nonce);
    }

    /** {@code configuredPrefix + nonce + "-"}, e.g. {@code SIM-K7Q2M3XW-}. */
    public static String prefixFor(String configuredPrefix, String nonce) {
        return configuredPrefix + nonce + "-";
    }

    /** Whether {@code code} starts with {@code prefix}, ignoring case (Magento compares codes case-insensitively). */
    public static boolean hasPrefix(String code, String prefix) {
        return code != null && prefix != null && code.length() >= prefix.length()
                && code.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** The last 4 characters, upper-cased (fewer if the code is shorter; empty for null). */
    public static String lastFour(String code) {
        if (code == null) {
            return "";
        }
        return code.substring(Math.max(0, code.length() - 4)).toUpperCase(Locale.ROOT);
    }

    /** {@code "****" + last4}: the only masked form, everywhere. */
    public static String masked(String last4) {
        return "****" + (last4 == null ? "" : last4);
    }

    /** {@code masked(lastFour(code))}; {@code null} stays {@code null}. */
    public static String maskedCode(String code) {
        return code == null ? null : masked(lastFour(code));
    }

    /**
     * Masks every code in {@code text}, in this order, each match becoming {@code ****} plus its last four characters:
     * <ol>
     *   <li>every case-insensitive occurrence of each known code (the codes sent or received in that call, the
     *       voucher's own, any minted ones);</li>
     *   <li>any token of our shape, {@code <prefix>-<base32 nonce>-<random>};</li>
     *   <li>any standalone run of 12 to 40 letters and digits holding both a letter and a digit: GetMore's text can
     *       echo a code without our prefix, and masking that relies on the prefix fails open exactly when a code is
     *       unexpected. A Magento report id ({@code webapi-<hex>}) is kept, so GetMore can find its log.</li>
     * </ol>
     * Used on every Magento message and every stored error before either reaches a log, a response or a row.
     */
    public static String maskAll(String text, Collection<String> knownCodes) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        if (knownCodes != null && !knownCodes.isEmpty()) {
            List<String> codes = new ArrayList<>();
            for (String code : knownCodes) {
                if (code != null && !code.isBlank()) {
                    codes.add(code.strip());
                }
            }
            codes.sort(Comparator.comparingInt(String::length).reversed()); // a longer code first: no partial overlap
            for (String code : codes) {
                masked = replaceAll(masked, Pattern.compile(Pattern.quote(code), Pattern.CASE_INSENSITIVE), false);
            }
        }
        masked = replaceAll(masked, OUR_SHAPE, false);
        return replaceAll(masked, BARE_RUN, true);
    }

    private static String replaceAll(String input, Pattern pattern, boolean bareRun) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String match = matcher.group();
            boolean keep = bareRun && (!hasLetterAndDigit(match) || isReportId(input, matcher.start()));
            matcher.appendReplacement(out, Matcher.quoteReplacement(keep ? match : masked(lastFour(match))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isReportId(String text, int start) {
        return start >= REPORT_ID_PREFIX.length()
                && text.regionMatches(true, start - REPORT_ID_PREFIX.length(), REPORT_ID_PREFIX, 0,
                REPORT_ID_PREFIX.length());
    }

    private static boolean hasLetterAndDigit(String s) {
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        return letter && digit;
    }
}
