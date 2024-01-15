package zw.co.reikan.loans.core.ndasenda.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.reikan.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl;

import java.time.LocalDateTime;

@RequiredArgsConstructor
@Component
@Slf4j
@Profile("scheduled-tasks")
public class NdasendaDeductionBatchCommitDailyJob {

    private final NdasendaLoanApprovalServiceImpl approvalService;

    @Scheduled(cron = "${ndasenda.commit.deduction.batch.requests.cron}")
    public void execute() {
        log.info("Committing open batch until now - {}", LocalDateTime.now());
        approvalService.commitDeductionRequestsUntilNow();
    }
}
