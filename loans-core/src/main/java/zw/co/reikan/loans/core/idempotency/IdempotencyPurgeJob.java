package zw.co.reikan.loans.core.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Hourly TTL sweep of {@code idempotency_records}. Runs alongside the existing
 * scheduled jobs (same {@code scheduled-tasks} profile) so only the designated
 * worker node executes it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Profile("scheduled-tasks")
public class IdempotencyPurgeJob {

    private final IdempotencyService idempotencyService;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void purgeExpiredRecords() {
        int purged = idempotencyService.purgeExpired();
        if (purged > 0) {
            log.info("Purged {} expired idempotency records", purged);
        }
    }
}
