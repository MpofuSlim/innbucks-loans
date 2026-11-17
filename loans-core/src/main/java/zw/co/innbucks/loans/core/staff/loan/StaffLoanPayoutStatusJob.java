package zw.co.innbucks.loans.core.staff.loan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.jobs.IdChunks;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Asks the bank about the cash Staff Grocery Loan payouts it took (BOOKED), may have taken (IN_DOUBT), or was
 * confirmed never to have booked and are still watched (FAILED within {@code failed-watch}), and holds claims never
 * settled: {@link StaffLoanPayoutStatusService}, which reads the bank's status and never sends a booking. It moves no
 * money, and depends on nothing that does ({@code StaffLoanPayoutWiringTest}).
 *
 * <p>On its own switch, not {@code STAFF_LOANS_JOBS_ENABLED}: it runs wherever payouts are paid in cash
 * ({@code loans.staff-loans.payout.method=CASH}), because a payout the bank took is only recorded paid by asking it, and
 * that switch also starts the Monday offer run. Every {@code status-check-interval} (PT3M), on the one scheduler
 * thread, so a run stops once the bank cannot be reached (no answer, a failed login, a 5xx) or the inquiry breaks: every
 * one behind it would most likely end the same way, and it must not hold that thread for long. The rest wait for the
 * next run. An answer about one loan that says nothing (a bare 404, an HTML page), or one that could not be saved, is
 * that loan's alone: it is counted and logged, the run goes on to the next, and the next run asks again. So no one loan
 * can hold the head of the queue.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "loans.staff-loans.payout", name = "method", havingValue = "CASH")
public class StaffLoanPayoutStatusJob {

    private final StaffLoanPayoutStatusService statusService;
    private final StaffLoanRepository loanRepository;
    private final StaffLoanProperties properties;
    private final StaffLoanPayoutMetrics metrics;
    private final MarketTimeZone marketTimeZone;

    public StaffLoanPayoutStatusJob(StaffLoanPayoutStatusService statusService, StaffLoanRepository loanRepository,
                                    StaffLoanProperties properties, StaffLoanPayoutMetrics metrics,
                                    MarketTimeZone marketTimeZone) {
        this.statusService = statusService;
        this.loanRepository = loanRepository;
        this.properties = properties;
        this.metrics = metrics;
        this.marketTimeZone = marketTimeZone;
        log.info("[startup] Staff Grocery Loan cash payouts are followed up with the bank every {}",
                properties.getPayout().getStatusCheckInterval());
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "${loans.staff-loans.payout.status-check-interval:PT3M}")
    public void run() {
        try {
            runOnce(marketTimeZone.nowUtc());
        } catch (RuntimeException ex) {
            // The one scheduler thread must never die.
            log.error("Staff loan payout status check failed", ex);
        }
    }

    /** @return whether every payout due was asked about (false: the run stopped early) */
    boolean runOnce(LocalDateTime now) {
        try {
            statusService.holdAbandonedClaims(now);
        } catch (RuntimeException ex) {
            log.error("Could not look for staff loan payouts claimed and never settled", ex);
        }
        LocalDateTime watchedSince = now.minus(properties.getPayout().getFailedWatch());
        boolean finished = IdChunks.forEach(loanRepository::findPayoutAwaitingInquiryIds, this::checkAll)
                && IdChunks.forEach((after, chunk) -> loanRepository.findPayoutFailedSinceIds(watchedSince, after,
                chunk), this::checkAll);
        try {
            metrics.backlog(loanRepository.payoutBacklog(), now);
        } catch (RuntimeException ex) {
            log.error("Could not read the staff loan payout backlog for its gauges", ex);
        }
        return finished;
    }

    private boolean checkAll(List<Long> ids) {
        for (int i = 0; i < ids.size(); i++) {
            StaffLoanPayoutStatusService.Checked checked;
            try {
                checked = statusService.check(ids.get(i));
            } catch (RuntimeException ex) {
                metrics.count(StaffLoanPayoutMetrics.Event.INQUIRY_ERROR);
                log.error("Staff loan payout status check broke at loan {}; the run stops", ids.get(i), ex);
                return false;
            }
            if (!checked.carriesOn()) {
                log.warn("Staff loan payout status check stopped at loan {} ({}); {} loan(s) of its chunk, and every"
                        + " later one, left for the next run", ids.get(i), checked, ids.size() - i - 1);
                return false;
            }
            if (checked == StaffLoanPayoutStatusService.Checked.UNREADABLE
                    || checked == StaffLoanPayoutStatusService.Checked.NOT_RECORDED) {
                log.warn("Staff loan payout status check: nothing recorded for loan {} ({}); asked again next run",
                        ids.get(i), checked);
            }
        }
        return true;
    }
}
