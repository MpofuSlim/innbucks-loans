package zw.co.innbucks.loans.core.auth;

import zw.co.innbucks.loans.core.exception.ValidationException;

import java.nio.charset.StandardCharsets;

/**
 * What a password a person chooses must be, the ticketing user-service's rule: 8 to 72 characters.
 *
 * <p>72 is bcrypt's limit: it reads the first 72 bytes and ignores the rest, so a longer password
 * would be accepted and then silently match any other with the same first 72 bytes. The check is on
 * bytes, so it holds for non-ASCII characters too.</p>
 *
 * <p>A password may not begin or end with whitespace. Change-password used to trim before hashing
 * while sign-in compared the password as typed, so a password chosen with a trailing space could
 * never be used; refusing the edge whitespace makes the two agree without guessing which was meant.</p>
 */
public final class PasswordPolicy {

    static final int MIN_LENGTH = 8;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** @throws ValidationException (400) naming the rule broken */
    public static void requireAcceptable(String password) {
        if (password == null || password.isEmpty()) {
            throw new ValidationException("A new password is required");
        }
        if (!password.equals(password.strip())) {
            throw new ValidationException("The password must not begin or end with a space");
        }
        if (password.length() < MIN_LENGTH) {
            throw new ValidationException("The password must be at least %d characters".formatted(MIN_LENGTH));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ValidationException("The password must be at most %d characters".formatted(MAX_BYTES));
        }
    }
}
