package zw.co.innbucks.loans.core.voucher;

import java.util.Optional;

/**
 * The voucher code's shapes (FR-SGL-033). The code is all digits, the last a {@link Damm} check digit, in groups of
 * four however long it is:
 * <ul>
 *   <li>{@link #scanValue}, digits only ({@code 1234567890123452}): what GetMore's till receives and what the SuperApp
 *       puts in the QR code, so a scanner types it exactly as if it were keyed;</li>
 *   <li>{@link #display}, {@code 1234 5678 9012 3452}: on screen;</li>
 *   <li>{@link #forMessage}, {@code 1234-5678-9012-3452}: in the SMS and WhatsApp, where a space can wrap;</li>
 *   <li>{@link #masked}, {@code **** **** **** 3452}: everywhere else (FR-SGL-040).</li>
 * </ul>
 * A code is accepted typed in any of these shapes, or with stray spaces and dashes.
 */
public final class VoucherCodes {

    /** The shortest and longest codes ever issued, whatever {@code loans.vouchers.code-length} is now. */
    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 24;
    static final int GROUP = 4;

    private VoucherCodes() {
    }

    /**
     * The code as digits only, if what was typed or scanned can be a voucher code: digits with any spaces or dashes,
     * {@value #MIN_LENGTH} to {@value #MAX_LENGTH} digits long, with a valid check digit. Anything else is empty, and
     * is refused without a lookup.
     */
    public static Optional<String> normalize(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        StringBuilder digits = new StringBuilder(typed.length());
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c != ' ' && c != '-') {
                return Optional.empty();
            }
        }
        if (digits.length() < MIN_LENGTH || digits.length() > MAX_LENGTH || !Damm.isValid(digits)) {
            return Optional.empty();
        }
        return Optional.of(digits.toString());
    }

    /** Digits only: the till's input and the QR code's content. */
    public static String scanValue(String code) {
        return code;
    }

    /** In groups of four, separated by spaces, for a screen. */
    public static String display(String code) {
        return grouped(code, ' ');
    }

    /** In groups of four, separated by dashes, for an SMS or WhatsApp. */
    public static String forMessage(String code) {
        return grouped(code, '-');
    }

    /** Every group but the last hidden, for a screen, a report or a log a person without the entitlement reads. */
    public static String masked(String last4, int length) {
        StringBuilder out = new StringBuilder();
        for (int group = 0; group < length / GROUP - 1; group++) {
            out.append("**** ");
        }
        return out.append(last4).toString();
    }

    static String lastFour(String code) {
        return code.substring(code.length() - GROUP);
    }

    private static String grouped(String code, char separator) {
        StringBuilder out = new StringBuilder(code.length() + code.length() / GROUP);
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % GROUP == 0) {
                out.append(separator);
            }
            out.append(code.charAt(i));
        }
        return out.toString();
    }
}
