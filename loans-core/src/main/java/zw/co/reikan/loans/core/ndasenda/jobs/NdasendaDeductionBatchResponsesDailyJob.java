package zw.co.reikan.loans.core.ndasenda.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.reikan.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl;
import zw.co.reikan.loans.core.ndasenda.ResponseSweepResult;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@RequiredArgsConstructor
@Component
@Slf4j
@Profile("scheduled-tasks")
public class NdasendaDeductionBatchResponsesDailyJob {

    private final NdasendaLoanApprovalServiceImpl approvalService;

    @Scheduled(cron = "${ndasenda.fetch.deduction.batch.responses.cron}")
    public void execute() {
        try {
            ResponseSweepResult run = approvalService.sweepDeductionResponses(LocalDateTime.now(ZoneOffset.UTC));
            if (run.incomplete()) {
                // Each failure was logged where it happened. This line marks the run itself: an incomplete
                // run is not a quiet one, and loans still waiting may have answers it could not read.
                log.error("Ndasenda response run {} to {} INCOMPLETE: {} fetch failure(s) {} - read {} batch(es),"
                                + " {} record(s); {} loan(s) awaiting Ndasenda, {} newly overdue",
                        run.from(), run.to(), run.fetchFailures().size(), run.fetchFailures(), run.batchesRead(),
                        run.recordsProcessed(), run.awaitingLoans(), run.newlyOverdue());
            } else {
                log.info("Ndasenda response run {} to {}: read {} batch(es), {} record(s); {} loan(s) awaiting"
                                + " Ndasenda, {} newly overdue",
                        run.from(), run.to(), run.batchesRead(), run.recordsProcessed(), run.awaitingLoans(),
                        run.newlyOverdue());
            }
        } catch (Exception ex) {
            // Kept inside the job: the next run starts again from the loans still waiting.
            log.error("Ndasenda response run failed ({})", ex.getClass().getSimpleName(), ex);
        }
    }
}
