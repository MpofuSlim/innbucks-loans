package zw.co.innbucks.loans.core.workflow;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.core.workflow.StageQueue.Waiting;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Escalates items waiting past their stage's escalation point (FR-SSB-014 / FR-PBL-030), once per wait: stamped on the
 * item, logged at ERROR, audited, and emailed to the stage's escalation roles and, where the stage says so, to the
 * item's assignee (for MORE_INFORMATION, the application's originator). One email per recipient per run.
 */
@Slf4j
@Service
public class WorkflowEscalationService {

    static final String ESCALATED = "WORK_ITEM_ESCALATED";
    static final String SYSTEM_ACTOR = "workflow-escalation-job";

    private final WorkflowStageRepository workflowStageRepository;
    private final StageQueues stageQueues;
    private final WorkQueueService workQueueService;
    private final WorkItemRepository workItemRepository;
    private final LoanRepository loanRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readTemplate;

    public WorkflowEscalationService(WorkflowStageRepository workflowStageRepository, StageQueues stageQueues,
                                     WorkQueueService workQueueService, WorkItemRepository workItemRepository,
                                     LoanRepository loanRepository, UserRepository userRepository,
                                     NotificationService notificationService, AuditService auditService,
                                     PlatformTransactionManager transactionManager) {
        this.workflowStageRepository = workflowStageRepository;
        this.stageQueues = stageQueues;
        this.workQueueService = workQueueService;
        this.workItemRepository = workItemRepository;
        this.loanRepository = loanRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTemplate = new TransactionTemplate(transactionManager);
        this.readTemplate.setReadOnly(true);
    }

    private record Escalated(WorkflowStage stage, String reference, long waitingHours, String assignee,
                             String originatorEmail) {
    }

    /** A wait past the escalation point whose item is not yet escalated: what the read leaves for the writes. */
    private record Due(Long loanId, LocalDateTime enteredAt) {
    }

    /**
     * Escalates every item past its stage's escalation point that has not been, each in its own transaction, so one
     * that fails leaves the others escalated.
     *
     * @return how many were escalated
     */
    public int escalateOverdue() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Escalated> escalated = new ArrayList<>();
        for (WorkflowStage stage : workflowStageRepository.findAllByOrderByDisplayOrderAscCodeAsc()) {
            if (!stage.isActive() || stage.getEscalationHours() == null) {
                continue;
            }
            StageQueue queue = stageQueues.of(stage);
            LocalDateTime cutoff = now.minusHours(stage.getEscalationHours());
            // Read in a transaction of its own: a checkpoint's queue reads each loan's channel, which is LAZY. Only
            // ids and times leave it; each escalation re-reads its loan under the lock.
            List<Due> due = readTemplate.execute(tx -> {
                List<Waiting> waiting = queue.waiting().stream()
                        .filter(wait -> !wait.enteredAt().isAfter(cutoff)).toList();
                Map<Long, WorkItem> items = workQueueService.currentItems(stage.getCode(), waiting);
                return waiting.stream()
                        .filter(wait -> {
                            WorkItem item = items.get(wait.loan().getId());
                            return item == null || item.getEscalatedAt() == null;
                        })
                        .map(wait -> new Due(wait.loan().getId(), wait.enteredAt()))
                        .toList();
            });
            for (Due wait : due == null ? List.<Due>of() : due) {
                try {
                    Escalated one = transactionTemplate.execute(
                            tx -> escalate(stage, queue, wait.loanId(), wait.enteredAt(), now));
                    if (one != null) {
                        escalated.add(one);
                    }
                } catch (RuntimeException ex) {
                    log.error("Could not escalate loan {}'s {} item; the next run tries again", wait.loanId(),
                            stage.getCode(), ex);
                }
            }
        }
        if (!escalated.isEmpty()) {
            email(escalated);
        }
        return escalated.size();
    }

    private Escalated escalate(WorkflowStage stage, StageQueue queue, Long loanId, LocalDateTime entered,
                               LocalDateTime now) {
        Loan loan = loanRepository.findByIdForUpdate(loanId).orElse(null);
        // Re-checked under the lock: the loan may have left the stage, or come back to it, since the queue was read.
        if (loan == null || queue.enteredAt(loan).filter(entered::equals).isEmpty()) {
            return null;
        }
        WorkItem item = workItemRepository.findByStageCodeAndLoanIdAndEnteredAt(stage.getCode(), loanId, entered)
                .orElseGet(() -> WorkQueueService.newItem(stage.getCode(), loanId, entered, now));
        if (item.getEscalatedAt() != null) {
            return null;
        }
        item.setEscalatedAt(now);
        WorkItem saved = workItemRepository.save(item);
        workQueueService.record(saved, WorkItemAction.ESCALATED, null, null, SYSTEM_ACTOR, now);
        long waited = WaitHours.between(entered, now).longValue();
        log.error("WORK ITEM ESCALATED: loan {} has waited {} hours at {} since {}, past the {}-hour escalation point"
                        + " (target {} hours); escalation notified (audited)", loan.getReference(), waited,
                stage.getCode(), entered, stage.getEscalationHours(), stage.getTargetHours());
        audit(loan, "stage=" + stage.getCode() + " waitingHours=" + waited + " since=" + entered + " targetHours="
                + stage.getTargetHours() + " escalationHours=" + stage.getEscalationHours()
                + (saved.getAssignedTo() == null ? "" : " assignedTo=" + saved.getAssignedTo()));
        User originator = loan.getCreatedByUser();
        return new Escalated(stage, loan.getReference(), waited, saved.getAssignedTo(),
                originator == null ? null : originator.getEmail());
    }

    /** One email per recipient per run; loan references only, never the applicants. */
    private void email(List<Escalated> escalated) {
        Map<UserGroup, List<String>> groupEmails = new EnumMap<>(UserGroup.class);
        Map<String, List<Escalated>> byRecipient = new LinkedHashMap<>();
        Map<String, String> assigneeEmails = assigneeEmails(escalated);
        for (Escalated one : escalated) {
            for (UserGroup group : one.stage().getEscalationRoles()) {
                groupEmails.computeIfAbsent(group, g -> userRepository.findByGroupsContaining(g).stream()
                                .map(User::getEmail).filter(StringUtils::isNotBlank).toList())
                        .forEach(email -> add(byRecipient, email, one));
            }
            String holder = holderEmail(one, assigneeEmails);
            if (StringUtils.isNotBlank(holder)) {
                add(byRecipient, holder, one);
            }
        }
        if (byRecipient.isEmpty()) {
            log.warn("{} workflow item(s) escalated, but nobody to notify has an email address", escalated.size());
            return;
        }
        byRecipient.forEach((recipient, items) -> {
            String subject = items.size() == 1
                    ? "Overdue: " + items.getFirst().stage().getName() + ", loan " + items.getFirst().reference()
                    : items.size() + " workflow items overdue";
            String body = "These items have waited past their escalation point:\n\n"
                    + items.stream()
                    .map(one -> one.stage().getName() + ": loan " + one.reference() + ", waiting " + one.waitingHours()
                            + " hours (escalation point " + one.stage().getEscalationHours() + " hours, target "
                            + one.stage().getTargetHours() + " hours)"
                            + (one.assignee() == null ? "" : ", with " + one.assignee()))
                    .collect(Collectors.joining("\n"))
                    + "\n\nThey are in the work queues.";
            try {
                notificationService.sendEmail(recipient, subject, body);
            } catch (RuntimeException ex) {
                log.error("Escalation email could not be queued", ex);
            }
        });
    }

    /**
     * Whoever holds the item, when the stage notifies them: its assignee, or at a stage worked by the originator, the
     * originator.
     */
    private static String holderEmail(Escalated one, Map<String, String> assigneeEmails) {
        if (!one.stage().isNotifyAssignee()) {
            return null;
        }
        if (one.assignee() != null) {
            return assigneeEmails.get(one.assignee().toLowerCase());
        }
        return one.stage().assignable() ? null : one.originatorEmail();
    }

    private static void add(Map<String, List<Escalated>> byRecipient, String email, Escalated one) {
        List<Escalated> items = byRecipient.computeIfAbsent(email.trim().toLowerCase(), key -> new ArrayList<>());
        if (!items.contains(one)) {
            items.add(one);
        }
    }

    private Map<String, String> assigneeEmails(List<Escalated> escalated) {
        Set<String> assignees = escalated.stream().filter(one -> one.stage().isNotifyAssignee())
                .map(Escalated::assignee).filter(StringUtils::isNotBlank).collect(Collectors.toSet());
        if (assignees.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByUsernameIn(assignees).stream()
                .filter(user -> StringUtils.isNotBlank(user.getEmail()))
                .collect(Collectors.toMap(user -> user.getUsername().toLowerCase(), User::getEmail, (a, b) -> a));
    }

    private void audit(Loan loan, String detail) {
        try {
            auditService.record(AuditLog.builder()
                    .eventType(ESCALATED)
                    .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                    .actorId(SYSTEM_ACTOR).channelUsed(SYSTEM_ACTOR)
                    .detail(detail)
                    .correlationId(loan.getReference()));
        } catch (RuntimeException ex) {
            log.error("Audit of loan {}'s escalation failed", loan.getId(), ex);
        }
    }
}
