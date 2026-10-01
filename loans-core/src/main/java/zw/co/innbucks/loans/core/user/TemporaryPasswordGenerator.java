package zw.co.innbucks.loans.core.user;

import java.security.SecureRandom;

/**
 * A temporary password for a lending portal user: an account just made for them, or a password reset. The ticketing
 * fleet's generator (user-service {@code TemporaryPasswordGenerator}), carried across so both systems send the same
 * shape; keep the two in step.
 *
 * <ul>
 *   <li><b>Letters and digits only.</b> The SMS gateway refuses {@code ! : / ? " * ;}, and the SMS path rewrites
 *       those and any other symbol it does not accept into a dot or a space, so the old generator's {@code ! * $}
 *       reached the user as a password that could never work ({@code !&8TQA4*4} arrived as {@code .&8TQA4 4}).</li>
 *   <li><b>No look-alikes:</b> no {@code 0/O/o} or {@code 1/l/I}, because the user types it off a phone.</li>
 *   <li><b>Two groups of five</b> joined by a hyphen, {@code Kp7rQ-n4mTx}, which the gateway carries and is easy to
 *       type. The hyphen is part of the password.</li>
 *   <li>10 characters over 56 symbols is about 58 bits, and the password has to be changed at the first sign-in.</li>
 * </ul>
 */
public final class TemporaryPasswordGenerator {

    // 24 upper (no I, O) + 24 lower (no l, o) + 8 digits (no 0, 1) = 56 symbols.
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RNG = new SecureRandom();
    private static final int GROUPS = 2;
    private static final int GROUP_LENGTH = 5;

    private TemporaryPasswordGenerator() {
    }

    /** A fresh value on every call, e.g. {@code Kp7rQ-n4mTx}. */
    public static String generate() {
        StringBuilder password = new StringBuilder(GROUPS * GROUP_LENGTH + GROUPS - 1);
        for (int group = 0; group < GROUPS; group++) {
            if (group > 0) {
                password.append('-');
            }
            for (int i = 0; i < GROUP_LENGTH; i++) {
                password.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
            }
        }
        return password.toString();
    }
}
