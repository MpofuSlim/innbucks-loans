package zw.co.reikan.loans.core.ndasenda.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.reikan.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl;

import java.time.LocalDate;

@RequiredArgsConstructor
@Component
@Slf4j
@Profile("scheduled-tasks")
public class NdasendaDeductionBatchResponsesDailyJob {

    private final NdasendaLoanApprovalServiceImpl approvalService;

    @Scheduled(cron = "${ndasenda.fetch.deduction.batch.responses.cron}")
    public void execute() {
        approvalService.processDeductionResponses(LocalDate.now());
    }
}
