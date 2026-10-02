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
 * partial purchase does not promise the whole amount again.</p>
 *
 * <p>It names the merchant the voucher can be spent at. A name too long to fit one SMS is left out rather than split
 * the message in two: the borrower already saw it on the quote and in their loan.</p>
 */
public final class VoucherMessage {

    public static final String TEMPLATE = "VOUCHER_ISSUED";
    public static final int VERSION = 2;
    static final int MAX_LENGTH = 160;

    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final String TEXT = "InnBucks Staff Grocery Loan. Your voucher is %s, worth %s %s, valid until %s.";
    private static final String AT_MERCHANT = " Spend it at %s.";
    private static final String AT_TILL = " Show it at the till.";

    private VoucherMessage() {
    }

    /**
     * @param validUntil   when the voucher lapses, on the market's clock: it is good for the whole of that day
     * @param merchantName where it can be spent
     */
    public static String text(String code, String currency, BigDecimal balance, OffsetDateTime validUntil,
                              String merchantName) {
        String text = String.format(Locale.ROOT, TEXT, VoucherCodes.forMessage(code), currency,
                balance.setScale(2, RoundingMode.HALF_UP).toPlainString(), UNTIL.format(validUntil));
        String named = text + String.format(Locale.ROOT, AT_MERCHANT, merchantName.strip());
        return named.length() <= MAX_LENGTH ? named : text + AT_TILL;
    }
}
