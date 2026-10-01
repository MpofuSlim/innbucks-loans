package zw.co.innbucks.loans.core.voucher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The message that carries a voucher to its customer (FR-SGL-034), by SMS and by WhatsApp alike, with its version for
 * the delivery log (FR-GEN-004). The code goes in full, dashed: it is the one place it is needed (FR-GEN-006). Worded
 * to pass the notification API unchanged (no {@code ! : / ? " * ;}) and, with a 16-digit code, to fit one 160-character
 * SMS, which {@code VoucherMessageTest} pins. A new wording is a new version.
 *
 * <p>It states what is left to spend, which on a voucher sent the first time is its face value, so a resend after a
 * partial purchase does not promise the whole amount again.
 */
public final class VoucherMessage {

    public static final String TEMPLATE = "VOUCHER_ISSUED";
    public static final int VERSION = 1;

    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final String TEXT = "InnBucks Staff Grocery Loan. Your GetMore voucher is %s, worth %s %s, valid"
            + " until %s. Show it at any GetMore till.";

    private VoucherMessage() {
    }

    /**
     * @param validUntil when the voucher lapses, on the market's clock: it is good for the whole of that day
     */
    public static String text(String code, String currency, BigDecimal balance, OffsetDateTime validUntil) {
        return String.format(Locale.ROOT, TEXT, VoucherCodes.forMessage(code), currency,
                balance.setScale(2, RoundingMode.HALF_UP).toPlainString(), UNTIL.format(validUntil));
    }
}
