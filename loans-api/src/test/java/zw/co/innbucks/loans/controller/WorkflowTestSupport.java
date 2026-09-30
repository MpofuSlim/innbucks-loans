package zw.co.innbucks.loans.controller;

import org.springframework.context.support.StaticApplicationContext;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.workflow.AssignmentMode;
import zw.co.innbucks.loans.core.workflow.Entitlement;
import zw.co.innbucks.loans.core.workflow.StageKind;
import zw.co.innbucks.loans.core.workflow.StageRole;
import zw.co.innbucks.loans.core.workflow.WorkflowAccess;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;
import zw.co.innbucks.loans.core.workflow.WorkflowStageRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code @PreAuthorize} interceptor as production runs it, resolving {@code @workflowAccess} against the workflow
 * stages as V12 seeds them (FR-SSB-014), so the web tests prove the configurable entitlements reproduce who could do
 * what before they became configurable.
 */
final class WorkflowTestSupport {

    private WorkflowTestSupport() {
    }

    /** The stages as seeded, by code. */
    static Map<String, WorkflowStage> seededStages() {
        return List.of(
                stage("PAYSLIP_REVIEW", 24, 48, role(UserGroup.CREDIT_MANAGER, Entitlement.VIEW),
                        role(UserGroup.CREDIT_MANAGER, Entitlement.WORK), role(UserGroup.CREDIT_MANAGER, Entitlement.ASSIGN)),
                stage("CREDIT_DECISION", 24, 48, role(UserGroup.CREDIT_MANAGER, Entitlement.VIEW),
                        role(UserGroup.CREDIT_MANAGER, Entitlement.WORK), role(UserGroup.CREDIT_MANAGER, Entitlement.ASSIGN)),
                stage("MORE_INFORMATION", 48, 96, role(UserGroup.CREDIT_MANAGER, Entitlement.VIEW)),
                stage("EMPLOYMENT_EVENT_REVIEW", 48, 96, role(UserGroup.CREDIT_MANAGER, Entitlement.VIEW),
                        role(UserGroup.FINANCE, Entitlement.VIEW), role(UserGroup.CREDIT_MANAGER, Entitlement.WORK),
                        role(UserGroup.CREDIT_MANAGER, Entitlement.ASSIGN)),
                stage("DEDUCTION_CANCELLATION", 24, 48, role(UserGroup.CREDIT_MANAGER, Entitlement.VIEW),
                        role(UserGroup.FINANCE, Entitlement.VIEW), role(UserGroup.FINANCE, Entitlement.WORK),
                        role(UserGroup.FINANCE, Entitlement.ASSIGN)))
                .stream().collect(Collectors.toMap(WorkflowStage::getCode, Function.identity()));
    }

    /** Access as seeded. */
    static WorkflowAccess seededAccess() {
        return accessOver(seededStages());
    }

    /** Access over these stages, read afresh on every check as in production. */
    static WorkflowAccess accessOver(Map<String, WorkflowStage> stages) {
        WorkflowStageRepository repository = mock(WorkflowStageRepository.class);
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(stages.get(i.<String>getArgument(0))));
        return new WorkflowAccess(repository);
    }

    /** The {@code @PreAuthorize} interceptor, resolving {@code @workflowAccess} to the stages as seeded. */
    static AuthorizationManagerBeforeMethodInterceptor preAuthorize() {
        return preAuthorize(seededAccess());
    }

    static AuthorizationManagerBeforeMethodInterceptor preAuthorize(WorkflowAccess access) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("workflowAccess", access);
        context.refresh();
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setApplicationContext(context);
        PreAuthorizeAuthorizationManager manager = new PreAuthorizeAuthorizationManager();
        manager.setExpressionHandler(handler);
        return AuthorizationManagerBeforeMethodInterceptor.preAuthorize(manager);
    }

    private static StageRole role(UserGroup group, Entitlement entitlement) {
        return new StageRole(group, entitlement);
    }

    private static WorkflowStage stage(String code, int targetHours, int escalationHours, StageRole... roles) {
        return WorkflowStage.builder().code(code).kind(StageKind.SYSTEM).name(code).displayOrder(0)
                .assignment("MORE_INFORMATION".equals(code) ? AssignmentMode.NONE : AssignmentMode.OPTIONAL)
                .targetHours(targetHours).escalationHours(escalationHours).notifyAssignee(true)
                .roles(new HashSet<>(Arrays.asList(roles))).escalationRoles(new HashSet<>(Set.of(UserGroup.SUPER_ADMIN)))
                .updatedBy("system").updatedAt(LocalDateTime.of(2026, 9, 30, 10, 0)).build();
    }
}
