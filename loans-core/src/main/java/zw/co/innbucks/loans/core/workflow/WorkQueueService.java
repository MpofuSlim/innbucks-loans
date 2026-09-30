package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.SegregationOfDuties;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.core.workflow.StageQueue.Waiting;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The workflow's queues (FR-SSB-014): what waits at each stage, against its service level, and who has it. Items are
 * assigned, reassigned and released here; every change is kept in {@code work_item_events}.
 *
 * <p>Who may do what: anyone who works a stage takes an unassigned item for themselves and releases their own; anyone
 * who assigns it gives any item to anyone who works the stage, and takes back or releases anyone's. An item is never
 * given to someone barred from deciding it by segregation of duties.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkQueueService {

    /** The {@code assignedTo} filter for the caller's own items. */
    public static final String ASSIGNED_TO_ME = "me";
    /** The {@code assignedTo} filter for items nobody has. */
    public static final String UNASSIGNED = "none";

    private final WorkflowStageService workflowStageService;
    private final WorkflowStageRepository workflowStageRepository;
    private final StageQueues stageQueues;
    private final WorkItemRepository workItemRepository;
    private final WorkItemEventRepository workItemEventRepository;
    private final LoanRepository loanRepository;
    private final UserRepository userRepository;
    private final AuthService authService;

    /** Every stage the caller may see, with its queue as it stands. */
    @Transactional(readOnly = true)
    public List<WorkQueueSummary> summaries() {
        User caller = authService.getLoggedInUser();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<WorkQueueSummary> summaries = new ArrayList<>();
        for (WorkflowStage stage : workflowStageRepository.findAllByOrderByDisplayOrderAscCodeAsc()) {
            if (!stage.isActive() || !stage.grants(caller.getGroups(), Entitlement.VIEW)) {
                continue;
            }
            List<Waiting> waiting = stageQueues.of(stage).waiting();
            Map<Long, WorkItem> items = currentItems(stage.getCode(), waiting);
            boolean assigned = assigns(stage);
            summaries.add(new WorkQueueSummary(stage.getCode(), stage.getName(), stage.getAssignment(),
                    stage.getTargetHours(), stage.getEscalationHours(), waiting.size(),
                    waiting.stream().filter(wait -> overdue(stage, wait.enteredAt(), now)).count(),
                    items.values().stream().filter(item -> item.getEscalatedAt() != null).count(),
                    assigned ? waiting.stream().filter(wait -> assigneeOf(items, wait) == null).count() : null,
                    assigned ? waiting.stream()
                            .filter(wait -> StringUtils.equalsIgnoreCase(assigneeOf(items, wait), caller.getUsername()))
                            .count() : null));
        }
        return summaries;
    }

    /**
     * The stage's items, oldest wait first.
     *
     * @param assignedTo {@value #ASSIGNED_TO_ME} for the caller's, {@value #UNASSIGNED} for nobody's, a username for
     *                   theirs, or null for all
     * @throws NotFoundException no such stage
     */
    @Transactional(readOnly = true)
    public List<WorkItemResponse> items(String code, String assignedTo) {
        WorkflowStage stage = workflowStageService.stage(code);
        List<Waiting> waiting = stageQueues.of(stage).waiting();
        Map<Long, WorkItem> items = currentItems(code, waiting);
        String wanted = ASSIGNED_TO_ME.equalsIgnoreCase(StringUtils.trimToEmpty(assignedTo))
                ? authService.getLoggedInUsername() : StringUtils.trimToNull(assignedTo);
        List<Waiting> shown = waiting.stream()
                .filter(wait -> wanted == null
                        || (UNASSIGNED.equalsIgnoreCase(wanted) ? assigneeOf(items, wait) == null
                        : StringUtils.equalsIgnoreCase(assigneeOf(items, wait), wanted)))
                .toList();
        return responses(stage, shown, items, LocalDateTime.now(ZoneOffset.UTC));
    }

    /** The items the caller has, at every stage they may still see, oldest wait first within each stage. */
    @Transactional(readOnly = true)
    public List<WorkItemResponse> mine() {
        User caller = authService.getLoggedInUser();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<WorkItemResponse> mine = new ArrayList<>();
        for (WorkflowStage stage : workflowStageRepository.findAllByOrderByDisplayOrderAscCodeAsc()) {
            if (!stage.isActive() || !stage.assignable() || !stage.grants(caller.getGroups(), Entitlement.VIEW)) {
                continue;
            }
            List<Waiting> waiting = stageQueues.of(stage).waiting();
            Map<Long, WorkItem> items = currentItems(stage.getCode(), waiting);
            mine.addAll(responses(stage, waiting.stream()
                    .filter(wait -> StringUtils.equalsIgnoreCase(assigneeOf(items, wait), caller.getUsername()))
                    .toList(), items, now));
        }
        return mine;
    }

    /**
     * Gives the loan's item at the stage to {@code assignee}, or to the caller when absent.
     *
     * @throws NotFoundException        no such stage or loan
     * @throws AccessDeniedException    the caller may not give it to that person
     * @throws ConflictException        the stage's items are not assigned, the loan is not waiting there, or it is
     *                                  someone else's and the caller may not take it
     * @throws IllegalArgumentException the assignee does not exist, does not work the stage, originated or is a party
     *                                  to the loan, or approved it at Credit and the stage follows that approval
     */
    @Transactional
    public WorkItemResponse assign(String code, Long loanId, String assignee) {
        WorkflowStage stage = workflowStageService.stage(code);
        requireAssigned(stage);
        User caller = authService.getLoggedInUser();
        boolean supervisor = stage.grants(caller.getGroups(), Entitlement.ASSIGN);
        String target = StringUtils.isBlank(assignee) ? caller.getUsername() : assignee.trim();
        boolean self = StringUtils.equalsIgnoreCase(target, caller.getUsername());
        if (!supervisor && !(self && stage.grants(caller.getGroups(), Entitlement.WORK))) {
            throw new AccessDeniedException(self ? "You do not work " + stage.getName()
                    : "You may take " + stage.getName() + " items for yourself, but not give them to others");
        }

        Loan loan = lockLoan(loanId);
        LocalDateTime entered = enteredAt(stage, loan);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        WorkItem item = workItemRepository.findByStageCodeAndLoanIdAndEnteredAt(code, loanId, entered)
                .orElseGet(() -> newItem(code, loanId, entered, now));
        String current = item.getAssignedTo();
        if (StringUtils.equalsIgnoreCase(target, current)) {
            return response(stage, loan, entered, item, now);
        }
        if (!supervisor && current != null) {
            throw new ConflictException(String.format("Loan %s's %s is assigned to %s; someone who assigns %s"
                    + " can reassign it", loan.getReference(), stage.getName(), current, stage.getName()));
        }
        User to = self ? caller : userRepository.findByUsername(target)
                .orElseThrow(() -> new IllegalArgumentException("No user " + target));
        if (!stage.grants(to.getGroups(), Entitlement.WORK)) {
            throw new IllegalArgumentException(to.getUsername() + " does not work " + stage.getName());
        }
        if (stage.segregated() && (SegregationOfDuties.originated(loan, to.getUsername())
                || SegregationOfDuties.isPartyTo(loan, to))) {
            throw new IllegalArgumentException(String.format("%s originated loan %s or is a party to it, so cannot"
                    + " be given its %s", to.getUsername(), loan.getReference(), stage.getName()));
        }
        if (stage.barsCreditApprover() && SegregationOfDuties.approved(loan, to.getUsername())) {
            throw new IllegalArgumentException(String.format("%s approved loan %s, so cannot be given its %s",
                    to.getUsername(), loan.getReference(), stage.getName()));
        }

        item.setAssignedTo(to.getUsername());
        item.setAssignedAt(now);
        WorkItem saved = workItemRepository.save(item);
        record(saved, current == null ? WorkItemAction.ASSIGNED : WorkItemAction.REASSIGNED, current,
                to.getUsername(), caller.getUsername(), now);
        log.info("Loan {}'s {} item {} to {} by {}", loan.getReference(), code,
                current == null ? "assigned" : "reassigned from " + current, to.getUsername(), caller.getUsername());
        return response(stage, loan, entered, saved, now);
    }

    /**
     * Takes the loan's item at the stage back from whoever has it. Releasing an item nobody has changes nothing.
     *
     * @throws NotFoundException     no such stage or loan
     * @throws AccessDeniedException it is someone else's and the caller does not assign the stage
     * @throws ConflictException     the stage's items are not assigned, or the loan is not waiting there
     */
    @Transactional
    public WorkItemResponse release(String code, Long loanId) {
        WorkflowStage stage = workflowStageService.stage(code);
        requireAssigned(stage);
        User caller = authService.getLoggedInUser();
        Loan loan = lockLoan(loanId);
        LocalDateTime entered = enteredAt(stage, loan);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Optional<WorkItem> found = workItemRepository.findByStageCodeAndLoanIdAndEnteredAt(code, loanId, entered);
        if (found.isEmpty() || found.get().getAssignedTo() == null) {
            return response(stage, loan, entered, found.orElse(null), now);
        }
        WorkItem item = found.get();
        String current = item.getAssignedTo();
        if (!StringUtils.equalsIgnoreCase(current, caller.getUsername())
                && !stage.grants(caller.getGroups(), Entitlement.ASSIGN)) {
            throw new AccessDeniedException("Loan " + loan.getReference() + "'s " + stage.getName() + " is "
                    + current + "'s; only they or someone who assigns " + stage.getName() + " can release it");
        }
        item.setAssignedTo(null);
        item.setAssignedAt(null);
        WorkItem saved = workItemRepository.save(item);
        record(saved, WorkItemAction.RELEASED, current, null, caller.getUsername(), now);
        log.info("Loan {}'s {} item released from {} by {}", loan.getReference(), code, current, caller.getUsername());
        return response(stage, loan, entered, saved, now);
    }

    /**
     * Takes the loan's current item at the stage back from {@code username} if they have it, recorded as their
     * release; nothing otherwise. For an action that hands the loan on, such as a referral to a higher credit authority
     * (FR-PBL-028), which must not leave it assigned to someone who can no longer decide it. The caller holds the loan's
     * lock.
     */
    @Transactional
    public void releaseIfHeldBy(SystemStage stage, Loan loan, String username) {
        String code = stage.name();
        workflowStageRepository.findById(code)
                .flatMap(configured -> stageQueues.of(configured).enteredAt(loan))
                .flatMap(entered -> workItemRepository.findByStageCodeAndLoanIdAndEnteredAt(code, loan.getId(), entered))
                .filter(item -> item.getAssignedTo() != null
                        && StringUtils.equalsIgnoreCase(item.getAssignedTo(), username))
                .ifPresent(item -> {
                    String current = item.getAssignedTo();
                    item.setAssignedTo(null);
                    item.setAssignedAt(null);
                    WorkItem saved = workItemRepository.save(item);
                    record(saved, WorkItemAction.RELEASED, current, null, username, LocalDateTime.now(ZoneOffset.UTC));
                    log.info("Loan {}'s {} item released from {} as it was handed on", loan.getReference(), code,
                            current);
                });
    }

    /**
     * Every assignment, reassignment, release and escalation of the loan's items, oldest first.
     *
     * @throws NotFoundException no such loan
     */
    @Transactional(readOnly = true)
    public List<WorkItemEventResponse> history(Long loanId) {
        if (!loanRepository.existsById(loanId)) {
            throw new NotFoundException("Loan " + loanId + " not found");
        }
        Map<Long, WorkItem> items = workItemRepository.findByLoanIdOrderByIdAsc(loanId).stream()
                .collect(Collectors.toMap(WorkItem::getId, Function.identity()));
        if (items.isEmpty()) {
            return List.of();
        }
        return workItemEventRepository.findByWorkItemIdInOrderByIdAsc(items.keySet()).stream()
                .map(event -> {
                    WorkItem item = items.get(event.getWorkItemId());
                    return new WorkItemEventResponse(event.getId(), item.getStageCode(), item.getLoanId(),
                            item.getEnteredAt(), event.getAction(), event.getFromUser(), event.getToUser(),
                            event.getPerformedBy(), event.getPerformedAt());
                })
                .toList();
    }

    /** Each waiting loan's item for its current wait, where it has one. */
    public Map<Long, WorkItem> currentItems(String code, List<Waiting> waiting) {
        if (waiting.isEmpty()) {
            return Map.of();
        }
        Map<Long, LocalDateTime> enteredAt = waiting.stream()
                .collect(Collectors.toMap(wait -> wait.loan().getId(), Waiting::enteredAt, (a, b) -> a));
        return workItemRepository.findByStageCodeAndLoanIdIn(code, enteredAt.keySet()).stream()
                .filter(item -> item.getEnteredAt().equals(enteredAt.get(item.getLoanId())))
                .collect(Collectors.toMap(WorkItem::getLoanId, Function.identity(), (a, b) -> a));
    }

    List<WorkItemResponse> responses(WorkflowStage stage, List<Waiting> waiting, Map<Long, WorkItem> items,
                                     LocalDateTime now) {
        Map<String, String> names = namesOf(Stream.concat(
                waiting.stream().map(wait -> wait.loan().getCreatedBy()),
                items.values().stream().map(WorkItem::getAssignedTo)).toList());
        return waiting.stream()
                .map(wait -> response(stage, wait.loan(), wait.enteredAt(), items.get(wait.loan().getId()), now, names))
                .toList();
    }

    private WorkItemResponse response(WorkflowStage stage, Loan loan, LocalDateTime entered, WorkItem item,
                                      LocalDateTime now) {
        List<String> usernames = new ArrayList<>();
        usernames.add(loan.getCreatedBy());
        if (item != null) {
            usernames.add(item.getAssignedTo());
        }
        return response(stage, loan, entered, item, now, namesOf(usernames));
    }

    private static WorkItemResponse response(WorkflowStage stage, Loan loan, LocalDateTime entered, WorkItem item,
                                             LocalDateTime now, Map<String, String> names) {
        LocalDateTime due = entered.plusHours(stage.getTargetHours());
        Channel channel = loan.getChannel();
        String assignedTo = item == null ? null : item.getAssignedTo();
        return new WorkItemResponse(stage.getCode(), loan.getId(), loan.getReference(),
                User.fullName(loan.getFirstName(), loan.getLastName()), loan.getPrincipal(),
                channel == null ? null : channel.getChannelId(), channel == null ? null : channel.getName(),
                loan.getCreatedBy(), loan.getCreatedBy() == null ? null : names.get(loan.getCreatedBy().toLowerCase()),
                entered, due,
                stage.getEscalationHours() == null ? null : entered.plusHours(stage.getEscalationHours()),
                WaitHours.between(entered, now), now.isAfter(due),
                item == null ? null : item.getEscalatedAt(),
                assignedTo, assignedTo == null ? null : names.get(assignedTo.toLowerCase()),
                item == null ? null : item.getAssignedAt());
    }

    /** Full names by lower-cased username. */
    private Map<String, String> namesOf(Collection<String> usernames) {
        Set<String> wanted = new HashSet<>();
        usernames.stream().filter(Objects::nonNull).forEach(wanted::add);
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByUsernameIn(wanted).stream()
                .filter(user -> user.fullName() != null)
                .collect(Collectors.toMap(user -> user.getUsername().toLowerCase(), User::fullName, (a, b) -> a));
    }

    public static boolean overdue(WorkflowStage stage, LocalDateTime entered, LocalDateTime now) {
        return now.isAfter(entered.plusHours(stage.getTargetHours()));
    }

    private static String assigneeOf(Map<Long, WorkItem> items, Waiting wait) {
        WorkItem item = items.get(wait.loan().getId());
        return item == null ? null : item.getAssignedTo();
    }

    static boolean assigns(WorkflowStage stage) {
        return stage.assignable() && stage.getAssignment() != AssignmentMode.NONE;
    }

    private static void requireAssigned(WorkflowStage stage) {
        if (!assigns(stage)) {
            throw new ConflictException(stage.getName() + " items are not assigned");
        }
    }

    private Loan lockLoan(Long loanId) {
        return loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new NotFoundException("Loan " + loanId + " not found"));
    }

    private LocalDateTime enteredAt(WorkflowStage stage, Loan loan) {
        return stageQueues.of(stage).enteredAt(loan).orElseThrow(() -> new ConflictException(
                "Loan " + loan.getReference() + " is not waiting at " + stage.getName()));
    }

    static WorkItem newItem(String code, Long loanId, LocalDateTime entered, LocalDateTime now) {
        return WorkItem.builder().stageCode(code).loanId(loanId).enteredAt(entered).createdAt(now).build();
    }

    void record(WorkItem item, WorkItemAction action, String from, String to, String by, LocalDateTime at) {
        workItemEventRepository.save(WorkItemEvent.builder()
                .workItemId(item.getId()).action(action).fromUser(from).toUser(to).performedBy(by).performedAt(at)
                .build());
    }
}
