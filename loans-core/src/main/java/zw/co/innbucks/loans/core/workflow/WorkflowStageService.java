package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The workflow's stages as configured (FR-SSB-014), changed by an administrator without a release and audited with
 * what they were. A change applies at once, to items already waiting as well.
 *
 * <p>Two things are not configurable, because they are what keep the workflow safe to configure: SUPER_ADMIN holds
 * every entitlement at every stage, so no change can lock the platform out of its own work; and AGENTS, who
 * originate applications, can hold none, so no change can let an originator assess or see the lender's queues.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowStageService {

    static final String WORKFLOW_STAGE_CHANGED = "WORKFLOW_STAGE_CHANGED";

    private final WorkflowStageRepository workflowStageRepository;
    private final AuthService authService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<WorkflowStageResponse> list() {
        return workflowStageRepository.findAllByOrderByDisplayOrderAsc().stream()
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
     * Replaces a stage's configuration.
     *
     * @throws NotFoundException        no such stage
     * @throws IllegalArgumentException a role AGENTS, an escalation point before the target, or assignment on a
     *                                  stage worked by the originator
     */
    @Transactional
    public WorkflowStageResponse update(String code, UpdateWorkflowStageRequest request) {
        WorkflowStage stage = stage(code);
        boolean assignable = SystemStage.of(code).map(SystemStage::assignable).orElse(true);
        refuseAgents(request.getViewRoles(), request.getWorkRoles(), request.getAssignRoles());
        if (request.getEscalateTo().contains(UserGroup.AGENTS)) {
            throw new IllegalArgumentException("Escalations cannot be sent to AGENTS");
        }
        if (request.getEscalationHours() != null && request.getEscalationHours() < request.getTargetHours()) {
            throw new IllegalArgumentException("Escalation hours cannot be fewer than the target hours");
        }
        if (!assignable && (request.getAssignment() != AssignmentMode.NONE
                || !withoutSuperAdmin(request.getWorkRoles()).isEmpty()
                || !withoutSuperAdmin(request.getAssignRoles()).isEmpty())) {
            throw new IllegalArgumentException(code + " is worked by each application's originator: its assignment"
                    + " must be NONE, and it has no work or assign roles");
        }

        String before = describe(stage);
        String username = authService.getLoggedInUsername();
        stage.setName(request.getName().trim());
        stage.setDescription(StringUtils.trimToNull(request.getDescription()));
        stage.setAssignment(request.getAssignment());
        stage.setTargetHours(request.getTargetHours());
        stage.setEscalationHours(request.getEscalationHours());
        stage.setNotifyAssignee(request.getNotifyAssignee());
        stage.getRoles().clear();
        withoutSuperAdmin(request.getViewRoles())
                .forEach(role -> stage.getRoles().add(new StageRole(role, Entitlement.VIEW)));
        withoutSuperAdmin(request.getWorkRoles())
                .forEach(role -> stage.getRoles().add(new StageRole(role, Entitlement.WORK)));
        withoutSuperAdmin(request.getAssignRoles())
                .forEach(role -> stage.getRoles().add(new StageRole(role, Entitlement.ASSIGN)));
        stage.getEscalationRoles().clear();
        stage.getEscalationRoles().addAll(request.getEscalateTo());
        stage.setUpdatedBy(username);
        stage.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
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

    @SafeVarargs
    private static void refuseAgents(Set<UserGroup>... roleSets) {
        for (Set<UserGroup> roles : roleSets) {
            if (roles.contains(UserGroup.AGENTS)) {
                throw new IllegalArgumentException("AGENTS originate applications and cannot be given a workflow stage");
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
        return "name:" + stage.getName()
                + ";assignment:" + stage.getAssignment()
                + ";view:" + names(stage.rolesWith(Entitlement.VIEW))
                + ";work:" + names(stage.rolesWith(Entitlement.WORK))
                + ";assign:" + names(stage.rolesWith(Entitlement.ASSIGN))
                + ";target:" + stage.getTargetHours() + "h"
                + ";escalation:" + (stage.getEscalationHours() == null ? "none" : stage.getEscalationHours() + "h")
                + ";escalateTo:" + names(stage.getEscalationRoles())
                + ";notifyAssignee:" + stage.isNotifyAssignee();
    }

    private static String names(Collection<UserGroup> roles) {
        return roles.isEmpty() ? "-" : roles.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }
}
