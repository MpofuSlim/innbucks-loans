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
    private AuthService authService;
    private StaffOfferMessagesService service;

    @BeforeEach
    void setUp() {
        StaffNotificationPreferenceRepository repository = mock(StaffNotificationPreferenceRepository.class);
        when(repository.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(stored));
        when(repository.save(any())).thenAnswer(i -> stored = i.getArgument(0));
        StaffMemberRepository memberRepository = mock(StaffMemberRepository.class);
        StaffMember member = StaffMember.builder().id(3L).employeeNumber("E1043").fullName("Tendai Moyo")
                .employmentStatus(StaffEmploymentStatus.ACTIVE).build();
        when(memberRepository.findByEmployeeNumber("E1043")).thenReturn(Optional.of(member));
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member));
        authService = mock(AuthService.class);
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

    @Test
    @DisplayName("in the SuperApp the member chooses for themselves: recorded as theirs, from the app, and audited")
    void borrowerChooses() {
        assertThat(service.forMember(3L)).isEqualTo(new BorrowerOfferMessages(false, null));
        when(authService.getLoggedInUsername()).thenReturn("borrower:E1043");

        BorrowerOfferMessages out = service.chooseForMember(3L, true);

        assertThat(out).isEqualTo(new BorrowerOfferMessages(true, NOW));
        assertThat(stored.getReason()).isEqualTo("Chosen in the SuperApp");
        assertThat(stored.getUpdatedBy()).isEqualTo("borrower:E1043");
        assertThat(service.get("E1043").optedOut()).as("staff see the member's own choice").isTrue();
        assertThat(service.chooseForMember(3L, false).optedOut()).isFalse();

        ArgumentCaptor<AuditLog.AuditLogBuilder> audits = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, times(2)).record(audits.capture());
        AuditLog first = audits.getAllValues().getFirst().build();
        assertThat(first.getEventType()).isEqualTo(StaffOfferMessagesService.OPTED_OUT);
        assertThat(first.getActorId()).isEqualTo("borrower:E1043");
        assertThat(first.getChannelUsed()).isEqualTo("superapp");
        assertThatThrownBy(() -> service.forMember(9L)).isInstanceOf(NotFoundException.class)
                .hasMessage("Staff member 9 is not on the staff register");
    }
}
