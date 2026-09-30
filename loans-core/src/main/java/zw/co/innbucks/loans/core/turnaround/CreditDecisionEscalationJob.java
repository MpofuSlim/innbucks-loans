package zw.co.innbucks.loans.core.turnaround;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Escalates credit decisions waiting past the escalation point (FR-PBL-030). */
@RequiredArgsConstructor
@Component
@Slf4j
@Profile("scheduled-tasks")
public class CreditDecisionEscalationJob {

    private final CreditTurnaroundService creditTurnaroundService;

    @Scheduled(fixedDelayString = "${loans.credit-escalation.interval:PT15M}",
            initialDelayString = "${loans.credit-escalation.initial-delay:PT1M}")
    public void execute() {
        try {
            int escalated = creditTurnaroundService.escalateOverdue();
            if (escalated > 0) {
                log.info("Escalated {} overdue credit decision(s)", escalated);
            }
        } catch (RuntimeException ex) {
            // Never lets an exception stop the shared scheduler thread; the next run tries again.
            log.error("Credit decision escalation run failed", ex);
        }
    }
}
