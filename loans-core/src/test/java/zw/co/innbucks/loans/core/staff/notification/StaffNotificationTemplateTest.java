package zw.co.innbucks.loans.core.staff.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.notifications.SmsTextSanitizer;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    @DisplayName("a paid-out notice names the amount, where it was paid by its last four digits, the repayment day and"
            + " the reference; a not-paid notice says nothing is owed")
    void loanWording() {
        assertThat(StaffNotificationTemplate.LOAN_PAID_OUT.paidOutText("USD", new BigDecimal("150"), "InnBucks wallet",
                "3123", LocalDate.of(2026, 11, 20), "SGL-2026-000143"))
                .isEqualTo("InnBucks paid your Staff Grocery Loan of USD 150.00 into your InnBucks wallet ending 3123."
                        + " Repaid from your salary on 20 Nov 2026. Ref SGL-2026-000143");
        assertThat(StaffNotificationTemplate.LOAN_NOT_PAID_OUT.notPaidOutText("SGL-2026-000143"))
                .isEqualTo("InnBucks did not pay out your Staff Grocery Loan SGL-2026-000143, so it is cancelled and"
                        + " nothing is owed. Log in to the InnBucks app for details.");
    }

    @Test
    @DisplayName("every wording passes the notification API unchanged and fits one 160-character SMS, even for a"
            + " six-figure limit and the longest account label")
    void smsSafe() {
        for (StaffNotificationTemplate template : StaffNotificationTemplate.values()) {
            String text = switch (template) {
                case OFFER_NEW, OFFER_REFRESHED -> template.offerText(new BigDecimal("999999.99"), LAPSES);
                case LOAN_PAID_OUT -> template.paidOutText("USD", new BigDecimal("99999.99"), "InnBucks account",
                        "1234", LocalDate.of(2026, 12, 20), "SGL-2026-9999999");
                case LOAN_NOT_PAID_OUT -> template.notPaidOutText("SGL-2026-9999999");
                case LAUNCH -> template.text();
            };
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

    @Test
    @DisplayName("a loan notice is about a loan, not an offer, and is worded only with its loan")
    void loanNoticesAreNotOffers() {
        assertThat(Arrays.stream(StaffNotificationTemplate.values()).filter(StaffNotificationTemplate::aboutALoan))
                .containsExactly(StaffNotificationTemplate.LOAN_PAID_OUT, StaffNotificationTemplate.LOAN_NOT_PAID_OUT);
        assertThat(StaffNotificationTemplate.LOAN_PAID_OUT.aboutAnOffer()).isFalse();
        assertThat(StaffNotificationTemplate.LOAN_NOT_PAID_OUT.aboutAnOffer()).isFalse();
        assertThat(StaffNotificationTemplate.LAUNCH.aboutALoan()).isFalse();
        assertThatThrownBy(StaffNotificationTemplate.LOAN_PAID_OUT::text).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StaffNotificationTemplate.LOAN_PAID_OUT.offerText(BigDecimal.ONE, LAPSES))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StaffNotificationTemplate.LOAN_NOT_PAID_OUT.paidOutText("USD", BigDecimal.ONE,
                "InnBucks wallet", "3123", LocalDate.of(2026, 11, 20), "SGL-2026-000143"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StaffNotificationTemplate.LOAN_PAID_OUT.notPaidOutText("SGL-2026-000143"))
                .isInstanceOf(IllegalStateException.class);
    }
}
