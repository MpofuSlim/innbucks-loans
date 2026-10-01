package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.channel.ChannelRepository;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.math.BigDecimal;
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
    private ChannelRepository channelRepository;
    private WorkflowStageService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowStageRepository.class);
        auditService = mock(AuditService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("admin");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        channelRepository = mock(ChannelRepository.class);
        service = new WorkflowStageService(repository, channelRepository, authService, auditService);
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
        when(repository.findAllByOrderByDisplayOrderAscCodeAsc()).thenReturn(List.of(
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
    @DisplayName("HUMAN_CAPITAL keeps the staff register: it can be given no loan stage and sent no escalation")
    void humanCapitalRefused() {
        when(repository.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL)));

        assertThatThrownBy(() -> service.update("CREDIT_DECISION",
                tightened().viewRoles(Set.of(UserGroup.CREDIT_MANAGER, UserGroup.HUMAN_CAPITAL)).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HUMAN_CAPITAL keeps the staff register and cannot be given a loan workflow stage");
        assertThatThrownBy(() -> service.update("CREDIT_DECISION",
                tightened().workRoles(Set.of(UserGroup.HUMAN_CAPITAL)).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update("CREDIT_DECISION",
                tightened().escalateTo(Set.of(UserGroup.HUMAN_CAPITAL)).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Escalations cannot be sent to HUMAN_CAPITAL");
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("the voucher roles, GETMORE and VOUCHER_SUPPORT, can be given no loan stage and sent no escalation")
    void voucherRolesRefused() {
        when(repository.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL)));

        for (UserGroup role : List.of(UserGroup.GETMORE, UserGroup.VOUCHER_SUPPORT)) {
            assertThatThrownBy(() -> service.update("CREDIT_DECISION",
                    tightened().workRoles(Set.of(role)).build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(role + " works on grocery vouchers and cannot be given a loan workflow stage");
            assertThatThrownBy(() -> service.update("CREDIT_DECISION",
                    tightened().escalateTo(Set.of(role)).build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Escalations cannot be sent to " + role);
        }
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

    private static CreateCheckpointStageRequest.CreateCheckpointStageRequestBuilder payoutCheck() {
        return CreateCheckpointStageRequest.builder().code("HIGH_VALUE_PAYOUT_CHECK")
                .holdPoint(HoldPoint.BEFORE_BOOKING).name(" High-value payout check ")
                .minimumPrincipal(new BigDecimal("2000.00"))
                .assignment(AssignmentMode.OPTIONAL)
                .viewRoles(Set.of(UserGroup.CREDIT_MANAGER))
                .workRoles(Set.of(UserGroup.FINANCE))
                .assignRoles(Set.of(UserGroup.FINANCE))
                .targetHours(4).escalationHours(8)
                .escalateTo(Set.of(UserGroup.SUPER_ADMIN, UserGroup.FINANCE)).notifyAssignee(true);
    }

    @Test
    @DisplayName("a checkpoint is created active from now, after the stage it follows, and audited")
    void checkpointCreated() {
        when(repository.existsById("HIGH_VALUE_PAYOUT_CHECK")).thenReturn(false);

        WorkflowStageResponse created = service.create(payoutCheck().minimumPrincipal(new BigDecimal("2000"))
                .channels(Set.of(" PORTAL ")).build());

        assertThat(created.kind()).isEqualTo(StageKind.CHECKPOINT);
        assertThat(created.name()).isEqualTo("High-value payout check");
        assertThat(created.holdPoint()).isEqualTo(HoldPoint.BEFORE_BOOKING);
        assertThat(created.displayOrder()).isEqualTo(35);
        assertThat(created.minimumPrincipal().toPlainString()).as("to the cent, as stored").isEqualTo("2000.00");
        assertThat(created.channels()).containsExactly("PORTAL");
        assertThat(created.active()).isTrue();
        assertThat(created.activeSince()).isEqualTo(created.updatedAt());
        assertThat(created.viewRoles()).containsExactly(UserGroup.SUPER_ADMIN, UserGroup.CREDIT_MANAGER,
                UserGroup.FINANCE);
        assertThat(created.workRoles()).containsExactly(UserGroup.SUPER_ADMIN, UserGroup.FINANCE);
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        AuditLog row = audit.getValue().build();
        assertThat(row.getEventType()).isEqualTo("WORKFLOW_STAGE_CREATED");
        assertThat(row.getEntityId()).isEqualTo("HIGH_VALUE_PAYOUT_CHECK");
        assertThat(row.getDetail()).isEqualTo("created=name:High-value payout check;assignment:OPTIONAL;"
                + "view:CREDIT_MANAGER;work:FINANCE;assign:FINANCE;target:4h;escalation:8h;"
                + "escalateTo:SUPER_ADMIN,FINANCE;notifyAssignee:true;holdPoint:BEFORE_BOOKING;"
                + "minimumPrincipal:2000.00;channels:PORTAL;active:true");
    }

    @Test
    @DisplayName("a checkpoint takes a given display order, and every channel when none is named")
    void checkpointDisplayOrderAndChannels() {
        WorkflowStageResponse created = service.create(payoutCheck().displayOrder(12).minimumPrincipal(null).build());

        assertThat(created.displayOrder()).isEqualTo(12);
        assertThat(created.channels()).isEmpty();
        assertThat(created.minimumPrincipal()).isNull();
    }

    @Test
    @DisplayName("a code already taken, by a checkpoint or a system stage, is a conflict; nothing is saved")
    void checkpointCodeTaken() {
        when(repository.existsById("HIGH_VALUE_PAYOUT_CHECK")).thenReturn(true);

        assertThatThrownBy(() -> service.create(payoutCheck().build()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Workflow stage HIGH_VALUE_PAYOUT_CHECK already exists");
        assertThatThrownBy(() -> service.create(payoutCheck().code("CREDIT_DECISION").build()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Workflow stage CREDIT_DECISION already exists");
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("a checkpoint's channels must exist; the same rules as any stage apply to its roles")
    void checkpointValidated() {
        when(channelRepository.findChannelByChannelId("superapp")).thenReturn(Optional.of(new Channel()));
        when(channelRepository.findChannelByChannelId("agents")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(payoutCheck().channels(Set.of("superapp", "agents")).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown channel agents; use a channel's id, or PORTAL for applications with no channel");
        assertThatThrownBy(() -> service.create(payoutCheck().workRoles(Set.of(UserGroup.AGENTS)).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AGENTS originate applications and cannot be given a workflow stage");
        assertThat(service.create(payoutCheck().channels(Set.of("superapp")).build()).channels())
                .containsExactly("superapp");
    }

    @Test
    @DisplayName("deactivating a checkpoint keeps when it was active; reactivating it starts its waits again from now")
    void checkpointDeactivatedAndReactivated() {
        WorkflowStage checkpoint = WorkflowFixtures.checkpoint("HIGH_VALUE_PAYOUT_CHECK", HoldPoint.BEFORE_BOOKING);
        when(repository.findById("HIGH_VALUE_PAYOUT_CHECK")).thenReturn(Optional.of(checkpoint));
        UpdateWorkflowStageRequest.UpdateWorkflowStageRequestBuilder same = tightened()
                .assignment(AssignmentMode.OPTIONAL).minimumPrincipal(new BigDecimal("5000.00"))
                .channels(Set.of("PORTAL"));

        WorkflowStageResponse off = service.update("HIGH_VALUE_PAYOUT_CHECK", same.active(false).build());
        assertThat(off.active()).isFalse();
        assertThat(off.activeSince()).isEqualTo(WorkflowFixtures.CHECKPOINT_ACTIVE_SINCE);
        assertThat(off.minimumPrincipal()).isEqualByComparingTo("5000");
        assertThat(off.channels()).containsExactly("PORTAL");

        WorkflowStageResponse unchanged = service.update("HIGH_VALUE_PAYOUT_CHECK", same.active(null).build());
        assertThat(unchanged.active()).isFalse();

        WorkflowStageResponse on =
                service.update("HIGH_VALUE_PAYOUT_CHECK", same.active(true).displayOrder(36).build());
        assertThat(on.active()).isTrue();
        assertThat(on.activeSince()).isNotEqualTo(WorkflowFixtures.CHECKPOINT_ACTIVE_SINCE)
                .isEqualTo(on.updatedAt());
        assertThat(on.displayOrder()).isEqualTo(36);
        assertThat(on.holdPoint()).isEqualTo(HoldPoint.BEFORE_BOOKING);
    }

    @Test
    @DisplayName("a system stage takes no minimum principal, channels or deactivation")
    void systemStageTakesNoCheckpointSettings() {
        when(repository.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL)));
        String refused = "CREDIT_DECISION is a system stage: it applies to every loan and is always active, so it takes"
                + " no minimum principal, channels or deactivation";

        assertThatThrownBy(() -> service.update("CREDIT_DECISION",
                tightened().minimumPrincipal(BigDecimal.TEN).build())).hasMessage(refused);
        assertThatThrownBy(() -> service.update("CREDIT_DECISION", tightened().channels(Set.of("PORTAL")).build()))
                .hasMessage(refused);
        assertThatThrownBy(() -> service.update("CREDIT_DECISION", tightened().active(false).build()))
                .hasMessage(refused);
        verify(repository, never()).save(any());

        WorkflowStageResponse changed = service.update("CREDIT_DECISION",
                tightened().active(true).channels(Set.of()).build());
        assertThat(changed.active()).isTrue();
        assertThat(changed.channels()).isNull();
        assertThat(changed.holdPoint()).isNull();
    }
}
