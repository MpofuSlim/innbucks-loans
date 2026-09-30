package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A stage's configuration is data, changed by an administrator and audited (FR-SSB-014). */
class WorkflowStageServiceTest {

    private WorkflowStageRepository repository;
    private AuditService auditService;
    private WorkflowStageService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowStageRepository.class);
        auditService = mock(AuditService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("admin");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new WorkflowStageService(repository, authService, auditService);
    }

    private static UpdateWorkflowStageRequest.UpdateWorkflowStageRequestBuilder tightened() {
        return UpdateWorkflowStageRequest.builder().name(" Credit decision ").description("  ")
                .assignment(AssignmentMode.EXCLUSIVE)
                .viewRoles(Set.of(UserGroup.CREDIT_MANAGER, UserGroup.FINANCE))
                .workRoles(Set.of(UserGroup.CREDIT_MANAGER, UserGroup.SUPER_ADMIN))
                .assignRoles(Set.of(UserGroup.CREDIT_MANAGER))
                .targetHours(8).escalationHours(16)
                .escalateTo(Set.of(UserGroup.SUPER_ADMIN, UserGroup.CREDIT_MANAGER)).notifyAssignee(false);
    }

    @Test
    @DisplayName("the stages are listed in pipeline order, each role list with SUPER_ADMIN and seeing including working")
    void listed() {
        when(repository.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(
                WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL), WorkflowFixtures.moreInformation()));

        List<WorkflowStageResponse> stages = service.list();

        assertThat(stages).extracting(WorkflowStageResponse::code).containsExactly("CREDIT_DECISION", "MORE_INFORMATION");
        assertThat(stages.getFirst().viewRoles()).containsExactly(UserGroup.SUPER_ADMIN, UserGroup.CREDIT_MANAGER);
        assertThat(stages.getFirst().workRoles()).containsExactly(UserGroup.SUPER_ADMIN, UserGroup.CREDIT_MANAGER);
        assertThat(stages.getFirst().escalateTo()).containsExactly(UserGroup.SUPER_ADMIN);
        assertThat(stages.get(1).workRoles()).isEmpty();
        assertThat(stages.get(1).assignRoles()).isEmpty();
    }

    @Test
    @DisplayName("a change replaces the whole configuration, stores no SUPER_ADMIN row, and is audited with what it was")
    void changeIsAudited() {
        when(repository.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL)));

        WorkflowStageResponse changed = service.update("CREDIT_DECISION", tightened().build());

        assertThat(changed.name()).isEqualTo("Credit decision");
        assertThat(changed.description()).isNull();
        assertThat(changed.assignment()).isEqualTo(AssignmentMode.EXCLUSIVE);
        assertThat(changed.viewRoles()).containsExactly(UserGroup.SUPER_ADMIN, UserGroup.CREDIT_MANAGER,
                UserGroup.FINANCE);
        assertThat(changed.workRoles()).containsExactly(UserGroup.SUPER_ADMIN, UserGroup.CREDIT_MANAGER);
        assertThat(changed.targetHours()).isEqualTo(8);
        assertThat(changed.escalationHours()).isEqualTo(16);
        assertThat(changed.notifyAssignee()).isFalse();
        assertThat(changed.updatedBy()).isEqualTo("admin");
        assertThat(changed.updatedAt()).isAfter(WorkflowFixtures.SEEDED_AT);
        ArgumentCaptor<WorkflowStage> saved = ArgumentCaptor.forClass(WorkflowStage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRoles()).extracting(StageRole::getUserGroup).doesNotContain(UserGroup.SUPER_ADMIN);
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        AuditLog row = audit.getValue().build();
        assertThat(row.getEventType()).isEqualTo("WORKFLOW_STAGE_CHANGED");
        assertThat(row.getEntityId()).isEqualTo("CREDIT_DECISION");
        assertThat(row.getActorId()).isEqualTo("admin");
        assertThat(row.getDetail()).isEqualTo("before=name:Credit decision;assignment:OPTIONAL;view:CREDIT_MANAGER;"
                + "work:CREDIT_MANAGER;assign:CREDIT_MANAGER;target:24h;escalation:48h;escalateTo:SUPER_ADMIN;"
                + "notifyAssignee:true after=name:Credit decision;assignment:EXCLUSIVE;view:CREDIT_MANAGER,FINANCE;"
                + "work:CREDIT_MANAGER;assign:CREDIT_MANAGER;target:8h;escalation:16h;"
                + "escalateTo:SUPER_ADMIN,CREDIT_MANAGER;notifyAssignee:false");
    }

    @Test
    @DisplayName("no escalation point means the stage is never escalated")
    void noEscalation() {
        when(repository.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL)));

        assertThat(service.update("CREDIT_DECISION", tightened().escalationHours(null).build()).escalationHours())
                .isNull();
    }

    @Test
    @DisplayName("AGENTS can be given no stage and sent no escalation; nothing is saved or audited")
    void agentsRefused() {
        when(repository.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL)));

        assertThatThrownBy(() -> service.update("CREDIT_DECISION",
                tightened().viewRoles(Set.of(UserGroup.AGENTS)).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AGENTS originate applications and cannot be given a workflow stage");
        assertThatThrownBy(() -> service.update("CREDIT_DECISION",
                tightened().assignRoles(Set.of(UserGroup.AGENTS)).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update("CREDIT_DECISION",
                tightened().escalateTo(Set.of(UserGroup.AGENTS)).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Escalations cannot be sent to AGENTS");
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("an escalation point before the target is refused")
    void escalationBeforeTarget() {
        when(repository.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL)));

        assertThatThrownBy(() -> service.update("CREDIT_DECISION", tightened().escalationHours(4).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Escalation hours cannot be fewer than the target hours");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("the stage the originator works cannot be assigned or given work roles")
    void originatorsStageIsNotAssigned() {
        when(repository.findById("MORE_INFORMATION")).thenReturn(Optional.of(WorkflowFixtures.moreInformation()));

        assertThatThrownBy(() -> service.update("MORE_INFORMATION", tightened().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("MORE_INFORMATION is worked by each application's originator");

        WorkflowStageResponse changed = service.update("MORE_INFORMATION", tightened()
                .assignment(AssignmentMode.NONE).workRoles(Set.of(UserGroup.SUPER_ADMIN)).assignRoles(Set.of())
                .build());
        assertThat(changed.viewRoles()).containsExactly(UserGroup.SUPER_ADMIN, UserGroup.CREDIT_MANAGER,
                UserGroup.FINANCE);
        assertThat(changed.workRoles()).isEmpty();
    }

    @Test
    @DisplayName("an unknown stage is a NotFoundException")
    void unknownStage() {
        when(repository.findById("BOOKING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("BOOKING"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No workflow stage BOOKING");
        assertThatThrownBy(() -> service.update("BOOKING", tightened().build()))
                .isInstanceOf(NotFoundException.class);
    }
}
