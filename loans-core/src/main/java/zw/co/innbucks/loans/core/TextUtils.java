package zw.co.innbucks.loans.core;

/** String helpers for identifiers and upstream messages. */
public final class TextUtils {

    private TextUtils() {
    }

    public static String trimSpecialCharacters(final String input) {
        if (input != null) {
            return input.replaceAll("[\\W\\s_]+", "");
        }
        return null;
    }

    public static String right(String input, int length) {
        if (input.length() >= length) {
            return input.substring(input.length() - length);
        }
        return input;
    }

    public static String left(String input, int length) {
        return input.length() <= length ? input : input.substring(0, length);
    }
}
