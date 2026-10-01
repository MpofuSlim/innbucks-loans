package zw.co.innbucks.loans.core.staff.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.notifications.SmsTextSanitizer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The staff notification wordings: what they say, and that each reaches a phone as written, in one SMS. */
class StaffNotificationTemplateTest {

    private static final OffsetDateTime LAPSES = OffsetDateTime.of(2026, 10, 12, 8, 0, 1, 0, ZoneOffset.ofHours(2));

    @Test
    @DisplayName("an offer message names the amount and when the offer lapses, on the market's clock")
    void offerWording() {
        assertThat(StaffNotificationTemplate.OFFER_NEW.offerText(new BigDecimal("300"), LAPSES))
                .isEqualTo("InnBucks Staff Grocery Loan. You have a new offer of up to USD 300.00, open until 08.00 on"
                        + " 12 Oct 2026. Log in to the InnBucks app to accept it.");
        assertThat(StaffNotificationTemplate.OFFER_REFRESHED.offerText(new BigDecimal("1250.5"), LAPSES))
                .isEqualTo("InnBucks Staff Grocery Loan. Your offer is renewed. You can borrow up to USD 1250.50 until"
                        + " 08.00 on 12 Oct 2026. Log in to the InnBucks app to accept it.");
        assertThat(StaffNotificationTemplate.LAUNCH.text()).isEqualTo("InnBucks has launched the Staff Grocery Loan"
                + " for staff. Log in to the InnBucks app to find out more.");
    }

    @Test
    @DisplayName("every wording passes the notification API unchanged and fits one 160-character SMS, even for a"
            + " six-figure limit")
    void smsSafe() {
        for (StaffNotificationTemplate template : StaffNotificationTemplate.values()) {
            String text = template.aboutAnOffer()
                    ? template.offerText(new BigDecimal("999999.99"), LAPSES)
                    : template.text();
            assertThat(SmsTextSanitizer.toGsmSafe(text)).as(template + " is changed by the sanitiser").isEqualTo(text);
            assertThat(text.length()).as(template + " length").isLessThanOrEqualTo(160);
            assertThat(template.title()).isNotBlank().hasSizeLessThanOrEqualTo(120);
            assertThat(template.version()).isPositive();
        }
    }

    @Test
    @DisplayName("an offer wording cannot be sent without its offer, nor the launch with one")
    void rightKindOfText() {
        assertThat(Arrays.stream(StaffNotificationTemplate.values()).filter(StaffNotificationTemplate::aboutAnOffer))
                .containsExactly(StaffNotificationTemplate.OFFER_NEW, StaffNotificationTemplate.OFFER_REFRESHED);
        assertThatThrownBy(StaffNotificationTemplate.OFFER_NEW::text).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StaffNotificationTemplate.LAUNCH.offerText(BigDecimal.ONE, LAPSES))
                .isInstanceOf(IllegalStateException.class);
    }
}
