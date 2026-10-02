package zw.co.innbucks.loans.core.staff.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.staff.StaffLoanJobs;

/**
 * Picks up staff notifications left PENDING, by a stop between a run's commit and their sending for instance. Only where
 * the Staff Grocery Loan jobs run ({@link StaffLoanJobs}); elsewhere {@code POST /staff-notifications/dispatch} does
 * the same. Only starts a pass on the dispatcher's own thread, so it holds the shared scheduler thread for no time at
 * all.
 */
@Component
@StaffLoanJobs
@RequiredArgsConstructor
public class StaffNotificationSweepJob {

    private final StaffNotificationDispatcher dispatcher;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${loans.staff-offers.notifications.sweep-interval:PT5M}")
    void sweep() {
        dispatcher.requestPass();
    }
}
