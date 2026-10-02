package zw.co.innbucks.loans.core.staff.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.staff.StaffLoanJobs;

/**
 * Emails the daily arrears report to Credit and Human Capital (FR-SGL-045), on {@code loans.staff-loans.arrears-
 * report-cron} read on the market's clock. Moves no money, so it runs where STAFF_LOANS_JOBS_ENABLED is on, or the
 * scheduled-tasks profile ({@link StaffLoanJobs}).
 */
@Slf4j
@Component
@StaffLoanJobs
@RequiredArgsConstructor
public class StaffLoanArrearsReportJob implements SchedulingConfigurer {

    private final StaffLoanArrearsService arrearsService;
    private final StaffLoanProperties properties;
    private final MarketTimeZone marketTimeZone;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(arrearsService::sendDaily, new CronTrigger(properties.getArrearsReportCron(),
                marketTimeZone.zone()));
        log.info("[startup] Daily Staff Grocery Loan arrears report scheduled for '{}' ({})",
                properties.getArrearsReportCron(), marketTimeZone.zone());
    }
}
