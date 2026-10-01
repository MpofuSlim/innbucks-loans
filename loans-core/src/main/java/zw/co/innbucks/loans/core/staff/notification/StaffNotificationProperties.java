package zw.co.innbucks.loans.core.staff.notification;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** How Staff Grocery Loan notifications are paced and capped (FR-SGL-021, FR-GEN-007). */
@Data
@Validated
@ConfigurationProperties(prefix = "loans.staff-offers.notifications")
public class StaffNotificationProperties {

    /**
     * The frequency cap (FR-SGL-021): a member who has let this many offers in a row lapse or be replaced unanswered is
     * no longer messaged about each new one. Their offers are still made and still appear in their in-app inbox.
     */
    @Min(value = 1, message = "loans.staff-offers.notifications.ignored-offers-before-cap must be at least 1")
    private int ignoredOffersBeforeCap = 3;

    /**
     * A capped member is still messaged when their last offer message is at least this many weeks old, so the cap
     * slows the messages down rather than ending them for good. 0 stops them for as long as the run of ignored offers
     * lasts: an offer that ends any other way (withdrawn, or taken up) breaks it.
     */
    @Min(value = 0, message = "loans.staff-offers.notifications.capped-reminder-weeks cannot be negative")
    private int cappedReminderWeeks = 4;

    /**
     * The most messages sent per second (FR-GEN-007), so the launch broadcast and a weekly run to the whole register do
     * not flood the SMS and WhatsApp gateways. 0 sends without pause.
     */
    @Min(value = 0, message = "loans.staff-offers.notifications.messages-per-second cannot be negative")
    private int messagesPerSecond = 5;
}
