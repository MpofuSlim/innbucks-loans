package zw.co.innbucks.loans.core.turnaround;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.NotFoundException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A stage's service level is data, changed by an administrator and audited (FR-PBL-030). */
class ServiceLevelServiceTest {

    private static final LocalDateTime SEEDED_AT = LocalDateTime.of(2026, 9, 30, 10, 0);

    private ServiceLevelRepository repository;
    private AuditService auditService;
    private ServiceLevelService service;

    @BeforeEach
    void setUp() {
        repository = mock(ServiceLevelRepository.class);
        auditService = mock(AuditService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("admin");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new ServiceLevelService(repository, authService, auditService);
    }

    private static ServiceLevel seeded() {
        return ServiceLevel.builder().stage(ServiceLevelStage.CREDIT_DECISION).targetHours(24).escalationHours(48)
                .updatedBy("system").updatedAt(SEEDED_AT).build();
    }

    @Test
    @DisplayName("the service levels are listed as stored")
    void listed() {
        when(repository.findAll()).thenReturn(List.of(seeded()));

        assertThat(service.list()).containsExactly(new ServiceLevelResponse(ServiceLevelStage.CREDIT_DECISION, 24, 48,
                "system", SEEDED_AT));
    }

    @Test
    @DisplayName("a change replaces both figures, names who made it, and is audited with what it was")
    void changeIsAudited() {
        when(repository.findById(ServiceLevelStage.CREDIT_DECISION)).thenReturn(Optional.of(seeded()));

        ServiceLevelResponse changed = service.update(ServiceLevelStage.CREDIT_DECISION,
                new UpdateServiceLevelRequest(8, 16));

        assertThat(changed.targetHours()).isEqualTo(8);
        assertThat(changed.escalationHours()).isEqualTo(16);
        assertThat(changed.updatedBy()).isEqualTo("admin");
        assertThat(changed.updatedAt()).isAfter(SEEDED_AT);
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        AuditLog row = audit.getValue().build();
        assertThat(row.getEventType()).isEqualTo("SERVICE_LEVEL_CHANGED");
        assertThat(row.getEntityId()).isEqualTo("CREDIT_DECISION");
        assertThat(row.getActorId()).isEqualTo("admin");
        assertThat(row.getDetail()).isEqualTo("before=target:24h,escalation:48h after=target:8h,escalation:16h");
    }

    @Test
    @DisplayName("an escalation point before the target is refused, and nothing is saved or audited")
    void escalationBeforeTargetIsRefused() {
        assertThatThrownBy(() -> service.update(ServiceLevelStage.CREDIT_DECISION, new UpdateServiceLevelRequest(24, 12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Escalation hours cannot be fewer than the target hours");
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("an escalation point equal to the target is allowed: escalated as soon as it is overdue")
    void escalationAtTheTargetIsAllowed() {
        when(repository.findById(ServiceLevelStage.CREDIT_DECISION)).thenReturn(Optional.of(seeded()));

        assertThat(service.update(ServiceLevelStage.CREDIT_DECISION, new UpdateServiceLevelRequest(12, 12))
                .escalationHours()).isEqualTo(12);
    }

    @Test
    @DisplayName("a stage with no row is a NotFoundException on change, and a configuration fault on read")
    void missingStage() {
        when(repository.findById(ServiceLevelStage.CREDIT_DECISION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ServiceLevelStage.CREDIT_DECISION, new UpdateServiceLevelRequest(8, 16)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.serviceLevel(ServiceLevelStage.CREDIT_DECISION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No service level is configured for CREDIT_DECISION");
    }
}
