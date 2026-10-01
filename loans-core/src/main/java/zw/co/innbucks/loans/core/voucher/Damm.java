package zw.co.innbucks.loans.core.voucher;

/**
 * The Damm check digit: catches every single wrong digit and every swap of two neighbouring digits, which Luhn does not
 * (it misses {@code 09} for {@code 90}). Chosen over Luhn for a second reason: a 16-digit Luhn-valid number is what card
 * number detectors look for, in the bank's scanners, log scrubbers and gateway filters, so a Luhn voucher code could be
 * flagged or blocked as a card number in places this service does not control.
 */
final class Damm {

    /** The totally anti-symmetric quasigroup of order 10 from Damm's thesis (2004). */
    private static final int[][] TABLE = {
            {0, 3, 1, 7, 5, 9, 8, 6, 4, 2},
            {7, 0, 9, 2, 1, 5, 4, 8, 6, 3},
            {4, 2, 0, 6, 8, 7, 1, 3, 5, 9},
            {1, 7, 5, 0, 9, 8, 3, 4, 2, 6},
            {6, 1, 2, 3, 0, 4, 5, 9, 7, 8},
            {3, 6, 7, 4, 2, 0, 9, 5, 8, 1},
            {5, 8, 6, 9, 7, 2, 0, 1, 3, 4},
            {8, 9, 4, 5, 3, 6, 2, 0, 1, 7},
            {9, 4, 3, 8, 6, 1, 7, 2, 0, 5},
            {2, 5, 8, 1, 4, 3, 6, 7, 9, 0}};

    private Damm() {
    }

    /** The digit to append to {@code digits}. */
    static char checkDigit(CharSequence digits) {
        return (char) ('0' + interim(digits));
    }

    /** Whether {@code digits}, its last digit the check digit, is valid. */
    static boolean isValid(CharSequence digits) {
        return !digits.isEmpty() && interim(digits) == 0;
    }

    private static int interim(CharSequence digits) {
        int interim = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Not a digit at position " + i);
            }
            interim = TABLE[interim][c - '0'];
        }
        return interim;
    }
}
