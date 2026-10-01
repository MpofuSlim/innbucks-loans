package zw.co.innbucks.loans.core.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.notifications.SmsTextSanitizer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The voucher message (FR-SGL-034): its exact wording, the dashed code, one SMS, and nothing the API would alter. */
class VoucherMessageTest {

    private static final OffsetDateTime UNTIL = OffsetDateTime.of(2026, 10, 31, 23, 59, 59, 0, ZoneOffset.ofHours(2));

    @Test
    @DisplayName("names the dashed code, what is left on it and the last day it is good for")
    void wording() {
        assertThat(VoucherMessage.text("4829150673318406", "USD", new BigDecimal("300"), UNTIL)).isEqualTo(
                "InnBucks Staff Grocery Loan. Your GetMore voucher is 4829-1506-7331-8406, worth USD 300.00, valid"
                        + " until 31 Oct 2026. Show it at any GetMore till.");
    }

    @Test
    @DisplayName("fits one 160-character SMS with a 16-digit code and passes the notification API unchanged")
    void oneSmsAndApiSafe() {
        String text = VoucherMessage.text("4829150673318406", "USD", new BigDecimal("12345.67"), UNTIL);
        assertThat(text.length()).isLessThanOrEqualTo(160);
        assertThat(SmsTextSanitizer.toGsmSafe(text)).isEqualTo(text);
        assertThat(text).doesNotContain("!", ":", "/", "?", "\"", "*", ";");
    }
}
