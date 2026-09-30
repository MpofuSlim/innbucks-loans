package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Escalates workflow items waiting past their stage's escalation point (FR-SSB-014 / FR-PBL-030). */
@RequiredArgsConstructor
@Component
@Slf4j
@Profile("scheduled-tasks")
public class WorkflowEscalationJob {

    private final WorkflowEscalationService workflowEscalationService;

    @Scheduled(fixedDelayString = "${loans.workflow-escalation.interval:PT15M}",
            initialDelayString = "${loans.workflow-escalation.initial-delay:PT1M}")
    public void execute() {
        try {
            int escalated = workflowEscalationService.escalateOverdue();
            if (escalated > 0) {
                log.info("Escalated {} overdue workflow item(s)", escalated);
            }
        } catch (RuntimeException ex) {
            // Never lets an exception stop the shared scheduler thread; the next run tries again.
            log.error("Workflow escalation run failed", ex);
        }
    }
}
