package zw.co.reikan.nanoloansweb.ndasenda.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.reikan.nanoloansweb.ndasenda.NdasendaLoanApprovalServiceImpl;

import java.time.LocalDate;

@RequiredArgsConstructor
@Component
@Slf4j
public class NdasendaDeductionBatchResponsesDailyJob {

    private final NdasendaLoanApprovalServiceImpl approvalService;

    @Scheduled(cron = "${ndasenda.fetch.deduction.batch.responses.cron}")
    public void execute() {
        approvalService.processDeductionResponses(LocalDate.now());
    }
}
