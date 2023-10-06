package zw.co.reikan.loans.core;

import java.time.LocalDateTime;
import java.time.ZoneId;

public class Utils {
    public static String trimSpecialCharacters(final String input) {
        if (input != null) {
            return input.replaceAll("[\\W\\s_]+", "");
        }
        return null;
    }

    public static String generateReference(String msisdn) {
        return right(msisdn, 9) + getEpochSecond();
    }

    private static long getEpochSecond() {
        return LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().getEpochSecond();
    }

    public static String right(String input, int length) {
        if (input.length() >= length) {
            return input.substring(input.length() - length);
        }
        return input;
    }

}
