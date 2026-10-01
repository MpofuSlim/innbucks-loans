package zw.co.innbucks.loans.core.staff.offer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;

import java.time.LocalDateTime;

/**
 * Starts offer runs, from the weekly schedule or the admin portal, and records one that breaks off: the run's own
 * transaction is rolled back with everything it did, so the failure is written in a transaction of its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaffOfferRunner {

    /** Who a scheduled run is recorded as started by. */
    static final String SCHEDULER = "scheduler";

    private final StaffOfferRunService runService;
    private final AuthService authService;
    private final MarketTimeZone marketTimeZone;

    /** A run started from the admin portal, by the signed-in user. */
    public StaffOfferRunResponse runNow() {
        return run(StaffOfferRunTrigger.MANUAL, authService.getLoggedInUsername());
    }

    /** The weekly run. */
    public StaffOfferRunResponse runScheduled() {
        return run(StaffOfferRunTrigger.SCHEDULED, SCHEDULER);
    }

    private StaffOfferRunResponse run(StaffOfferRunTrigger trigger, String startedBy) {
        LocalDateTime startedAt = marketTimeZone.nowUtc();
        try {
            return runService.run(trigger, startedBy);
        } catch (ConflictException inProgress) {
            throw inProgress;
        } catch (RuntimeException failure) {
            log.error("Staff offer run ({}, by {}) failed; nothing it did was kept", trigger, startedBy, failure);
            try {
                runService.recordFailure(trigger, startedBy, startedAt, failure);
            } catch (RuntimeException recording) {
                log.error("Could not record the failed staff offer run", recording);
            }
            throw failure;
        }
    }
}
