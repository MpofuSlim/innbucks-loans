package zw.co.innbucks.loans.core.getmore.live;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Magento's REST timestamps: {@code "yyyy-MM-dd HH:mm:ss"} with no offset, in UTC for coupons, orders and credit
 * memos; {@code "yyyy-MM-dd"} for a rule's dates. Parsed explicitly here, because this client is not built from Boot's
 * customised mapper.
 */
public final class MagentoTimes {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private MagentoTimes() {
    }

    /**
     * A UTC timestamp, or null for null or blank. A {@code T} separator is accepted too.
     *
     * @throws java.time.format.DateTimeParseException when it is neither
     */
    public static LocalDateTime parseUtc(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value.strip().replace('T', ' '), DATE_TIME);
    }

    /** A rule date; a timestamp's date part is accepted. Null for null or blank. */
    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
    }

    /** As Magento writes and filters on it: {@code 2026-11-13 21:59:59}. */
    public static String formatUtc(LocalDateTime value) {
        return value.withNano(0).format(DATE_TIME);
    }
}
