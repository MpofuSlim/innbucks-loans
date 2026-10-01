package zw.co.innbucks.loans.core.staff.offer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatusChanged;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Reading offers as a person would, and an offer withdrawn the moment its holder leaves (FR-SGL-007). */
class StaffOfferServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 10, 0);

    private StaffOfferRepository offerRepository;
    private AuditService auditService;
    private StaffOfferService service;

    @BeforeEach
    void setUp() {
        offerRepository = mock(StaffOfferRepository.class);
        auditService = mock(AuditService.class);
        service = new StaffOfferService(offerRepository, mock(StaffMemberRepository.class), auditService,
                new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)));
    }

    private static StaffOffer offer(LocalDateTime expiresAt) {
        return StaffOffer.builder().id(1L).staffMemberId(7L).runId(1L).cycleStart(LocalDate.of(2026, 10, 5))
                .grade("C4").scoreBand("Band C").gradeLimitChangeId(1L).amount(new BigDecimal("300.00"))
                .issuedAt(LocalDateTime.of(2026, 10, 5, 6, 0, 1)).expiresAt(expiresAt)
                .status(StaffOfferStatus.ACTIVE).version(0L).build();
    }

    @Test
    @DisplayName("an open offer past its expiry reads as EXPIRED, closed at its expiry; one still open reads as ACTIVE")
    void effectiveStatus() {
        StaffMember member = StaffMember.builder().id(7L).employeeNumber("E1001").fullName("Nyasha Dube").build();

        StaffOfferResponse lapsed = StaffOfferResponse.of(offer(NOW.minusMinutes(1)), member, NOW);
        StaffOfferResponse open = StaffOfferResponse.of(offer(NOW.plusDays(5)), member, NOW);

        assertThat(lapsed.status()).isEqualTo(StaffOfferStatus.EXPIRED);
        assertThat(lapsed.closedAt()).isEqualTo(NOW.minusMinutes(1));
        assertThat(open.status()).isEqualTo(StaffOfferStatus.ACTIVE);
        assertThat(open.closedAt()).isNull();
        assertThat(open.employeeNumber()).isEqualTo("E1001");
        assertThat(open.fullName()).isEqualTo("Nyasha Dube");
        assertThat(StaffOfferResponse.of(offer(NOW), member, NOW).status()).as("expiring now is expired")
                .isEqualTo(StaffOfferStatus.EXPIRED);
    }

    @Test
    @DisplayName("a member who stops being ACTIVE loses their open offer at once, audited as the approver")
    void withdrawsOnLeaving() {
        StaffOffer open = offer(NOW.plusDays(5));
        when(offerRepository.findByStaffMemberIdAndStatus(7L, StaffOfferStatus.ACTIVE)).thenReturn(Optional.of(open));

        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(7L, "E1001", StaffEmploymentStatus.ACTIVE,
                StaffEmploymentStatus.RESIGNED, 13L, "hc2"));

        assertThat(open.getStatus()).isEqualTo(StaffOfferStatus.WITHDRAWN);
        assertThat(open.getClosedAt()).isEqualTo(NOW);
        assertThat(open.getClosedReason()).isEqualTo("Employment status changed to RESIGNED");
        verify(offerRepository).save(open);
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        AuditLog logged = audit.getValue().build();
        assertThat(logged.getEventType()).isEqualTo(StaffOfferService.WITHDRAWN);
        assertThat(logged.getActorId()).isEqualTo("hc2");
        assertThat(logged.getDetail()).isEqualTo("employee:E1001;batch:13;reason:Employment status changed to"
                + " RESIGNED");
    }

    @Test
    @DisplayName("returning to ACTIVE, or holding no open offer, changes nothing")
    void nothingToWithdraw() {
        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(7L, "E1001",
                StaffEmploymentStatus.SUSPENDED, StaffEmploymentStatus.ACTIVE, 13L, "hc2"));
        when(offerRepository.findByStaffMemberIdAndStatus(any(), any())).thenReturn(Optional.empty());
        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(7L, "E1001", StaffEmploymentStatus.ACTIVE,
                StaffEmploymentStatus.UNPAID_LEAVE, 13L, "hc2"));

        verify(offerRepository, times(1)).findByStaffMemberIdAndStatus(7L, StaffOfferStatus.ACTIVE);
        verify(offerRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }
}
