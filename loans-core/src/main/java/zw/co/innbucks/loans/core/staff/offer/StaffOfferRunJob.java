package zw.co.innbucks.loans.core.staff.offer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.staff.StaffLoanJobs;

/**
 * The weekly Offer Generation run (FR-SGL-015), on {@code loans.staff-offers.run-cron} read on the market's clock, so
 * "Mondays at 08:00" is 08:00 in Harare for a ZW cell whatever the server's clock. Runs where the scheduled-tasks
 * profile is on, or where STAFF_LOANS_JOBS_ENABLED is ({@link StaffLoanJobs}).
 */
@Slf4j
@Component
@StaffLoanJobs
@RequiredArgsConstructor
public class StaffOfferRunJob implements SchedulingConfigurer {

    private final StaffOfferRunner runner;
    private final StaffOfferProperties properties;
    private final MarketTimeZone marketTimeZone;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(this::execute, new CronTrigger(properties.getRunCron(), marketTimeZone.zone()));
        log.info("[startup] Weekly staff offer run scheduled for '{}' ({})", properties.getRunCron(),
                marketTimeZone.zone());
    }

    void execute() {
        try {
            runner.runScheduled();
        } catch (ConflictException inProgress) {
            log.warn("Weekly staff offer run skipped: {}", inProgress.getMessage());
        } catch (RuntimeException failure) {
            // Already logged and recorded; never lets an exception stop the shared scheduler thread.
            log.debug("Weekly staff offer run failed", failure);
        }
    }
}
