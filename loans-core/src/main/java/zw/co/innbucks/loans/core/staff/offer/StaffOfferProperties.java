package zw.co.innbucks.loans.core.staff.offer;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

/** The weekly offer run's settings (FR-SGL-015, FR-SGL-018), env-configurable per cell. */
@Data
@Validated
@ConfigurationProperties(prefix = "loans.staff-offers")
public class StaffOfferProperties {

    /**
     * When the weekly run fires, as a Spring cron (second minute hour day month weekday) read on the market's clock:
     * Mondays at 08:00 Harare time by default. It fires only where the scheduled-tasks profile is on; a run can always
     * be started from the admin portal.
     */
    private String runCron = "0 0 8 * * MON";

    /** How many days an offer stays open before it lapses unaccepted (FR-SGL-018). */
    @Min(value = 1, message = "loans.staff-offers.validity-days must be at least 1")
    private int validityDays = 7;

    /**
     * How recent the latest payroll reconciliation must be, in days, for a run to go ahead. The register is the only
     * credit control for this product; one not checked against the payroll for longer than this is not lent on.
     */
    @Min(value = 1, message = "loans.staff-offers.max-reconciliation-age-days must be at least 1")
    private int maxReconciliationAgeDays = 35;

    @AssertTrue(message = "loans.staff-offers.run-cron is not a valid cron expression (second minute hour day month"
            + " weekday, e.g. 0 0 8 * * MON)")
    public boolean isRunCronValid() {
        return runCron != null && CronExpression.isValidExpression(runCron);
    }
}
