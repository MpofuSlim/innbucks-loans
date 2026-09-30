package zw.co.innbucks.loans.core.draft;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes open application drafts nobody has saved within the expiry period (FR-SSB-002). */
@RequiredArgsConstructor
@Component
@Slf4j
@Profile("scheduled-tasks")
public class LoanApplicationDraftExpiryJob {

    private final LoanApplicationDraftService draftService;

    @Scheduled(cron = "${loans.drafts.expiry-cron:0 30 1 * * *}")
    public void execute() {
        int deleted = draftService.deleteExpired();
        if (deleted > 0) {
            log.info("Deleted {} expired application draft(s)", deleted);
        }
    }
}
