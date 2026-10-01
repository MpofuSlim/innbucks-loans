package zw.co.innbucks.loans.core.voucher;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Random;

/**
 * New voucher codes (FR-SGL-033): every digit but the last from a cryptographically secure random source, so a code is
 * neither sequential nor guessable from another, and the last a {@link Damm} check digit, so a code mistyped at a till
 * is refused before it is looked up. The first digit is never 0: a till or spreadsheet that reads the code as a number
 * would drop it.
 */
@Component
class VoucherCodeGenerator {

    private final Random random;

    VoucherCodeGenerator() {
        this(new SecureRandom());
    }

    /** With the source of digits; a test passes a seeded one. */
    VoucherCodeGenerator(Random random) {
        this.random = random;
    }

    String next(int length) {
        if (length < VoucherCodes.MIN_LENGTH || length > VoucherCodes.MAX_LENGTH || length % VoucherCodes.GROUP != 0) {
            throw new IllegalArgumentException("A voucher code is 12 to 24 digits, in groups of four: " + length);
        }
        StringBuilder code = new StringBuilder(length);
        code.append((char) ('1' + random.nextInt(9)));
        while (code.length() < length - 1) {
            code.append((char) ('0' + random.nextInt(10)));
        }
        return code.append(Damm.checkDigit(code)).toString();
    }
}
