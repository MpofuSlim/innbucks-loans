package zw.co.innbucks.loans.core.staff.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Opting a member out of offer messages and back in (FR-SGL-022): recorded with the reason, and audited. */
class StaffOfferMessagesServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 8, 20, 31);

    private StaffNotificationPreference stored;
    private AuditService auditService;
    private StaffOfferMessagesService service;

    @BeforeEach
    void setUp() {
        StaffNotificationPreferenceRepository repository = mock(StaffNotificationPreferenceRepository.class);
        when(repository.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(stored));
        when(repository.save(any())).thenAnswer(i -> stored = i.getArgument(0));
        StaffMemberRepository memberRepository = mock(StaffMemberRepository.class);
        when(memberRepository.findByEmployeeNumber("E1043")).thenReturn(Optional.of(StaffMember.builder().id(3L)
                .employeeNumber("E1043").fullName("Tendai Moyo").employmentStatus(StaffEmploymentStatus.ACTIVE)
                .build()));
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("hc1");
        auditService = mock(AuditService.class);
        service = new StaffOfferMessagesService(repository, memberRepository, authService, auditService,
                new MarketTimeZone("ZW", Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)));
    }

    private static StaffOfferMessagesRequest request(boolean optedOut, String reason) {
        StaffOfferMessagesRequest request = new StaffOfferMessagesRequest();
        request.setOptedOut(optedOut);
        request.setReason(reason);
        return request;
    }

    @Test
    @DisplayName("a member with no setting receives messages; opting out and back in is recorded and audited")
    void optOutAndIn() {
        assertThat(service.get(" e1043 ").optedOut()).isFalse();
        assertThat(service.get("E1043").reason()).isNull();

        StaffOfferMessagesResponse out = service.set("e1043", request(true, "  Asked by phone  "));

        assertThat(out.optedOut()).isTrue();
        assertThat(out.reason()).isEqualTo("Asked by phone");
        assertThat(out.updatedBy()).isEqualTo("hc1");
        assertThat(out.updatedAt()).isEqualTo(NOW);
        assertThat(stored.getStaffMemberId()).isEqualTo(3L);

        StaffOfferMessagesResponse in = service.set("E1043", request(false, "Wants the messages again"));
        assertThat(in.optedOut()).isFalse();
        assertThat(service.get("E1043").reason()).isEqualTo("Wants the messages again");

        ArgumentCaptor<AuditLog.AuditLogBuilder> audits = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, times(2)).record(audits.capture());
        assertThat(audits.getAllValues()).extracting(builder -> builder.build().getEventType())
                .containsExactly(StaffOfferMessagesService.OPTED_OUT, StaffOfferMessagesService.OPTED_IN);
        assertThat(audits.getAllValues().getFirst().build().getEntityId()).isEqualTo("E1043");
        assertThat(audits.getAllValues().getFirst().build().getDetail()).isEqualTo("reason:Asked by phone");
    }

    @Test
    @DisplayName("an employee not on the register is a 404")
    void unknown() {
        assertThatThrownBy(() -> service.set("E9999", request(true, "x"))).isInstanceOf(NotFoundException.class)
                .hasMessage("Employee E9999 is not on the staff register");
        assertThatThrownBy(() -> service.get("E9999")).isInstanceOf(NotFoundException.class);
    }
}
