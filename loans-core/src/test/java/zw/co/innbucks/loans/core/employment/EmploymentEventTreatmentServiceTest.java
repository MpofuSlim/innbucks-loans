package zw.co.innbucks.loans.core.employment;

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

/** Each type of employment event's treatment is data, changed by an administrator and audited (FR-SSB-024). */
class EmploymentEventTreatmentServiceTest {

    private EmploymentEventTreatmentRepository repository;
    private AuditService auditService;
    private EmploymentEventTreatmentService service;

    @BeforeEach
    void setUp() {
        repository = mock(EmploymentEventTreatmentRepository.class);
        auditService = mock(AuditService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("admin");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new EmploymentEventTreatmentService(repository, authService, auditService);
    }

    private static EmploymentEventTreatment seeded(EmploymentEventType type, ApplicationTreatment applications,
                                                   LoanTreatment loans, boolean notify) {
        return EmploymentEventTreatment.builder().eventType(type).applicationTreatment(applications)
                .loanTreatment(loans).notifyOnDecline(notify).updatedBy("system")
                .updatedAt(LocalDateTime.of(2026, 9, 30, 10, 0)).build();
    }

    @Test
    @DisplayName("the treatments are listed in the order the event types are declared")
    void listedInDeclaredOrder() {
        when(repository.findAll()).thenReturn(List.of(
                seeded(EmploymentEventType.DEATH_IN_SERVICE, ApplicationTreatment.DECLINE, LoanTreatment.REVIEW, false),
                seeded(EmploymentEventType.TRANSFER, ApplicationTreatment.CONTINUE, LoanTreatment.NONE, true),
                seeded(EmploymentEventType.SUSPENSION, ApplicationTreatment.HOLD, LoanTreatment.REVIEW, true)));

        assertThat(service.list()).extracting(EmploymentEventTreatmentResponse::eventType).containsExactly(
                EmploymentEventType.TRANSFER, EmploymentEventType.SUSPENSION, EmploymentEventType.DEATH_IN_SERVICE);
    }

    @Test
    @DisplayName("a change replaces the whole treatment, names who made it, and is audited with what it was")
    void changeIsAudited() {
        EmploymentEventTreatment secondment =
                seeded(EmploymentEventType.SECONDMENT, ApplicationTreatment.HOLD, LoanTreatment.REVIEW, true);
        when(repository.findById(EmploymentEventType.SECONDMENT)).thenReturn(Optional.of(secondment));

        EmploymentEventTreatmentResponse changed = service.update(EmploymentEventType.SECONDMENT,
                new UpdateEmploymentEventTreatmentRequest(ApplicationTreatment.CONTINUE, LoanTreatment.REVIEW, false));

        assertThat(changed.applicationTreatment()).isEqualTo(ApplicationTreatment.CONTINUE);
        assertThat(changed.loanTreatment()).isEqualTo(LoanTreatment.REVIEW);
        assertThat(changed.notifyOnDecline()).isFalse();
        assertThat(changed.updatedBy()).isEqualTo("admin");
        assertThat(changed.updatedAt()).isAfter(LocalDateTime.of(2026, 9, 30, 10, 0));
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        AuditLog row = audit.getValue().build();
        assertThat(row.getEventType()).isEqualTo("EMPLOYMENT_EVENT_TREATMENT_CHANGED");
        assertThat(row.getEntityId()).isEqualTo("SECONDMENT");
        assertThat(row.getActorId()).isEqualTo("admin");
        assertThat(row.getDetail()).isEqualTo("before=applications:HOLD,loans:REVIEW,notifyOnDecline:true"
                + " after=applications:CONTINUE,loans:REVIEW,notifyOnDecline:false");
    }

    @Test
    @DisplayName("a type with no treatment on file is not found, and nothing is audited")
    void missingTreatment() {
        assertThatThrownBy(() -> service.update(EmploymentEventType.PROMOTION,
                new UpdateEmploymentEventTreatmentRequest(ApplicationTreatment.CONTINUE, LoanTreatment.NONE, true)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.treatmentFor(EmploymentEventType.PROMOTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No treatment is configured for PROMOTION");
        verifyNoInteractions(auditService);
    }
}
