package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.channel.ChannelRepository;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The workflow's stages as configured (FR-SSB-014), changed by an administrator without a release and audited with
 * what they were. A change applies at once, to items already waiting as well. An administrator can also add
 * checkpoint stages, which hold loans at a {@link HoldPoint} until cleared or declined.
 *
 * <p>Two things are not configurable, because they are what keep the workflow safe to configure: SUPER_ADMIN holds
 * every entitlement at every stage, so no change can lock the platform out of its own work; and AGENTS, who
 * originate applications, can hold none, so no change can let an originator assess or see the lender's queues.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowStageService {

    /** Roles that work on grocery vouchers only: never given a loan workflow stage or sent its escalations. */
    private static final Set<UserGroup> VOUCHER_ROLES = EnumSet.of(UserGroup.MERCHANT_TILL, UserGroup.VOUCHER_SUPPORT);

    static final String WORKFLOW_STAGE_CHANGED = "WORKFLOW_STAGE_CHANGED";
    static final String WORKFLOW_STAGE_CREATED = "WORKFLOW_STAGE_CREATED";

    private final WorkflowStageRepository workflowStageRepository;
    private final ChannelRepository channelRepository;
    private final AuthService authService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<WorkflowStageResponse> list() {
        return workflowStageRepository.findAllByOrderByDisplayOrderAscCodeAsc().stream()
                .map(WorkflowStageResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowStageResponse get(String code) {
        return WorkflowStageResponse.of(stage(code));
    }

    /**
     * The stage as stored.
     *
     * @throws NotFoundException no such stage
     */
    @Transactional(readOnly = true)
    public WorkflowStage stage(String code) {
        return workflowStageRepository.findById(code)
                .orElseThrow(() -> new NotFoundException("No workflow stage " + code));
    }

    /**
     * Adds a checkpoint stage. It holds loans from now, including those already at its point.
     *
     * @throws ConflictException        a stage with the code already exists
     * @throws IllegalArgumentException a role AGENTS, an escalation point before the target, or an unknown channel
     */
    @Transactional
    public WorkflowStageResponse create(CreateCheckpointStageRequest request) {
        String code = request.getCode().trim();
        if (workflowStageRepository.existsById(code) || SystemStage.of(code).isPresent()) {
            throw new ConflictException("Workflow stage " + code + " already exists");
        }
        validate(request);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String username = authService.getLoggedInUsername();
        WorkflowStage stage = WorkflowStage.builder()
                .code(code)
                .kind(StageKind.CHECKPOINT)
                .holdPoint(request.getHoldPoint())
                .displayOrder(request.getDisplayOrder() == null ? request.getHoldPoint().displayOrder()
                        : request.getDisplayOrder())
                .active(true)
                .activeSince(now)
                .build();
        apply(stage, request, username, now);
        WorkflowStage saved = workflowStageRepository.save(stage);

        String created = describe(saved);
        log.info("Checkpoint stage {} created by {}: {}", code, username, created);
        auditService.record(AuditLog.builder()
                .eventType(WORKFLOW_STAGE_CREATED)
                .entityType("WORKFLOW_STAGE").entityId(code)
                .actorId(username).channelUsed("admin-portal")
                .detail("created=" + created));
        return WorkflowStageResponse.of(saved);
    }

    /**
     * Replaces a stage's configuration. A checkpoint's code and point stay as created; deactivating it lifts its hold
     * at once, and reactivating it holds again the loans at its point that it never decided.
     *
     * @throws NotFoundException        no such stage
     * @throws IllegalArgumentException a role AGENTS, an escalation point before the target, assignment on a stage
     *                                  worked by the originator, an unknown channel, or checkpoint settings on a
     *                                  system stage
     */
    @Transactional
    public WorkflowStageResponse update(String code, UpdateWorkflowStageRequest request) {
        WorkflowStage stage = stage(code);
        if (!stage.isCheckpoint() && (request.getMinimumPrincipal() != null
                || (request.getChannels() != null && !request.getChannels().isEmpty())
                || Boolean.FALSE.equals(request.getActive()))) {
            throw new IllegalArgumentException(code + " is a system stage: it applies to every loan and is always"
                    + " active, so it takes no minimum principal, channels or deactivation");
        }
        validate(request);
        if (!stage.assignable() && (request.getAssignment() != AssignmentMode.NONE
                || !withoutSuperAdmin(request.getWorkRoles()).isEmpty()
                || !withoutSuperAdmin(request.getAssignRoles()).isEmpty())) {
            throw new IllegalArgumentException(code + " is worked by each application's originator: its assignment"
                    + " must be NONE, and it has no work or assign roles");
        }

        String before = describe(stage);
        String username = authService.getLoggedInUsername();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (request.getDisplayOrder() != null) {
            stage.setDisplayOrder(request.getDisplayOrder());
        }
        if (stage.isCheckpoint() && request.getActive() != null && request.getActive() != stage.isActive()) {
            stage.setActive(request.getActive());
            if (request.getActive()) {
                // Loans already at its point wait from now, as when it was first added.
                stage.setActiveSince(now);
            }
        }
        apply(stage, request, username, now);
        WorkflowStage saved = workflowStageRepository.save(stage);

        String after = describe(saved);
        log.info("Workflow stage {} changed by {}: {} -> {}", code, username, before, after);
        auditService.record(AuditLog.builder()
                .eventType(WORKFLOW_STAGE_CHANGED)
                .entityType("WORKFLOW_STAGE").entityId(code)
                .actorId(username).channelUsed("admin-portal")
                .detail("before=" + before + " after=" + after));
        return WorkflowStageResponse.of(saved);
    }

    private void validate(StageSettings settings) {
        refuseAgents(settings.getViewRoles(), settings.getWorkRoles(), settings.getAssignRoles());
        if (settings.getEscalateTo().contains(UserGroup.AGENTS)) {
            throw new IllegalArgumentException("Escalations cannot be sent to AGENTS");
        }
        if (settings.getEscalateTo().contains(UserGroup.HUMAN_CAPITAL)) {
            throw new IllegalArgumentException("Escalations cannot be sent to HUMAN_CAPITAL");
        }
        for (UserGroup voucherRole : VOUCHER_ROLES) {
            if (settings.getEscalateTo().contains(voucherRole)) {
                throw new IllegalArgumentException("Escalations cannot be sent to " + voucherRole);
            }
        }
        if (settings.getEscalationHours() != null && settings.getEscalationHours() < settings.getTargetHours()) {
            throw new IllegalArgumentException("Escalation hours cannot be fewer than the target hours");
        }
        if (settings.getChannels() != null) {
            for (String channel : settings.getChannels()) {
                String id = channel.trim();
                if (!WorkflowStage.PORTAL.equals(id) && channelRepository.findChannelByChannelId(id).isEmpty()) {
                    throw new IllegalArgumentException("Unknown channel " + id + "; use a channel's id, or "
                            + WorkflowStage.PORTAL + " for applications with no channel");
                }
            }
        }
    }

    private static void apply(WorkflowStage stage, StageSettings settings, String username, LocalDateTime now) {
        stage.setName(settings.getName().trim());
        stage.setDescription(StringUtils.trimToNull(settings.getDescription()));
        stage.setAssignment(settings.getAssignment());
        stage.setTargetHours(settings.getTargetHours());
        stage.setEscalationHours(settings.getEscalationHours());
        stage.setNotifyAssignee(settings.getNotifyAssignee());
        stage.getRoles().clear();
        withoutSuperAdmin(settings.getViewRoles())
                .forEach(role -> stage.getRoles().add(new StageRole(role, Entitlement.VIEW)));
        withoutSuperAdmin(settings.getWorkRoles())
                .forEach(role -> stage.getRoles().add(new StageRole(role, Entitlement.WORK)));
        withoutSuperAdmin(settings.getAssignRoles())
                .forEach(role -> stage.getRoles().add(new StageRole(role, Entitlement.ASSIGN)));
        stage.getEscalationRoles().clear();
        stage.getEscalationRoles().addAll(settings.getEscalateTo());
        if (stage.isCheckpoint()) {
            // Money to the cent, as the column holds it, so the response and the audit say what was stored.
            stage.setMinimumPrincipal(settings.getMinimumPrincipal() == null ? null
                    : settings.getMinimumPrincipal().setScale(2, RoundingMode.UNNECESSARY));
            stage.getChannels().clear();
            if (settings.getChannels() != null) {
                settings.getChannels().stream().map(String::trim).forEach(stage.getChannels()::add);
            }
        }
        stage.setUpdatedBy(username);
        stage.setUpdatedAt(now);
    }

    @SafeVarargs
    private static void refuseAgents(Set<UserGroup>... roleSets) {
        for (Set<UserGroup> roles : roleSets) {
            if (roles.contains(UserGroup.AGENTS)) {
                throw new IllegalArgumentException("AGENTS originate applications and cannot be given a workflow stage");
            }
            if (roles.contains(UserGroup.HUMAN_CAPITAL)) {
                throw new IllegalArgumentException(
                        "HUMAN_CAPITAL keeps the staff register and cannot be given a loan workflow stage");
            }
            for (UserGroup voucherRole : VOUCHER_ROLES) {
                if (roles.contains(voucherRole)) {
                    throw new IllegalArgumentException(
                            voucherRole + " works on grocery vouchers and cannot be given a loan workflow stage");
                }
            }
        }
    }

    /** SUPER_ADMIN holds every entitlement anyway, so it is never stored. */
    private static Set<UserGroup> withoutSuperAdmin(Collection<UserGroup> roles) {
        Set<UserGroup> stored = EnumSet.noneOf(UserGroup.class);
        stored.addAll(roles);
        stored.remove(UserGroup.SUPER_ADMIN);
        return stored;
    }

    /** The configuration in one line, for the log and the audit trail. */
    static String describe(WorkflowStage stage) {
        String described = "name:" + stage.getName()
                + ";assignment:" + stage.getAssignment()
                + ";view:" + names(stage.rolesWith(Entitlement.VIEW))
                + ";work:" + names(stage.rolesWith(Entitlement.WORK))
                + ";assign:" + names(stage.rolesWith(Entitlement.ASSIGN))
                + ";target:" + stage.getTargetHours() + "h"
                + ";escalation:" + (stage.getEscalationHours() == null ? "none" : stage.getEscalationHours() + "h")
                + ";escalateTo:" + names(stage.getEscalationRoles())
                + ";notifyAssignee:" + stage.isNotifyAssignee();
        if (!stage.isCheckpoint()) {
            return described;
        }
        return described
                + ";holdPoint:" + stage.getHoldPoint()
                + ";minimumPrincipal:" + (stage.getMinimumPrincipal() == null ? "any"
                : stage.getMinimumPrincipal().toPlainString())
                + ";channels:" + (stage.getChannels().isEmpty() ? "all" : String.join(",",
                new TreeSet<>(stage.getChannels())))
                + ";active:" + stage.isActive();
    }

    private static String names(Collection<UserGroup> roles) {
        return roles.isEmpty() ? "-" : roles.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }
}
