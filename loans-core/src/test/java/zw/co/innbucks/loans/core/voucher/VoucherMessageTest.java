package zw.co.innbucks.loans.core.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.notifications.SmsTextSanitizer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The voucher message (FR-SGL-034): its exact wording, the dashed code, the merchant it is spent at, one SMS, and
 * nothing the API would alter.
 */
class VoucherMessageTest {

    private static final OffsetDateTime UNTIL = OffsetDateTime.of(2026, 10, 31, 23, 59, 59, 0, ZoneOffset.ofHours(2));

    @Test
    @DisplayName("names the dashed code, what is left on it, the last day it is good for and where to spend it")
    void wording() {
        assertThat(VoucherMessage.text("4829150673318406", "USD", new BigDecimal("300"), UNTIL, "GetMore Groceries"))
                .isEqualTo("InnBucks Staff Grocery Loan. Your voucher is 4829-1506-7331-8406, worth USD 300.00, valid"
                        + " until 31 Oct 2026. Spend it at GetMore Groceries.");
    }

    @Test
    @DisplayName("fits one 160-character SMS with a 16-digit code and passes the notification API unchanged")
    void oneSmsAndApiSafe() {
        String text = VoucherMessage.text("4829150673318406", "USD", new BigDecimal("12345.67"), UNTIL,
                "GetMore Groceries");
        assertThat(text.length()).isLessThanOrEqualTo(VoucherMessage.MAX_LENGTH);
        assertThat(SmsTextSanitizer.toGsmSafe(text)).isEqualTo(text);
        assertThat(text).doesNotContain("!", ":", "/", "?", "\"", "*", ";");
    }

    @Test
    @DisplayName("a merchant name too long for one SMS is left out rather than split the message in two")
    void longMerchantNameLeftOut() {
        String name = "The Zimbabwe Consolidated Grocery and Household Supplies Company";
        String text = VoucherMessage.text("4829150673318406", "USD", new BigDecimal("12345.67"), UNTIL, name);
        assertThat(text).isEqualTo("InnBucks Staff Grocery Loan. Your voucher is 4829-1506-7331-8406, worth USD"
                + " 12345.67, valid until 31 Oct 2026. Show it at the till.");
        assertThat(text.length()).isLessThanOrEqualTo(VoucherMessage.MAX_LENGTH);
        assertThat(SmsTextSanitizer.toGsmSafe(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("a name that just fits is kept, one character more is not")
    void nameAtTheLimit() {
        String base = VoucherMessage.text("4829150673318406", "USD", new BigDecimal("300"), UNTIL, "X");
        int room = VoucherMessage.MAX_LENGTH - base.length() + 1;
        assertThat(VoucherMessage.text("4829150673318406", "USD", new BigDecimal("300"), UNTIL, "M".repeat(room)))
                .endsWith("Spend it at " + "M".repeat(room) + ".").hasSize(VoucherMessage.MAX_LENGTH);
        assertThat(VoucherMessage.text("4829150673318406", "USD", new BigDecimal("300"), UNTIL,
                "M".repeat(room + 1))).endsWith("Show it at the till.");
    }
}
