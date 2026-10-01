package zw.co.innbucks.loans.core.staff.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.offer.StaffOffer;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chipo Banda's SuperApp inbox (FR-SGL-019, FR-SGL-020) on the morning of 1 October: the notification for offer 31,
 * still open, the one for offer 12, which lapsed, and the launch. Each says whether its offer can still be taken up.
 */
class StaffInboxServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 7, 1, 55);
    private static final long CHIPO = 2L;

    private final StaffNotificationRepository notifications = mock(StaffNotificationRepository.class);
    private final StaffOfferRepository offers = mock(StaffOfferRepository.class);
    private final StaffInboxService service = new StaffInboxService(notifications, offers,
            new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-10-01T07:01:55Z"), ZoneOffset.UTC)));

    private final StaffNotification launch = notification(1388L, StaffNotificationTemplate.LAUNCH, null, null);
    private final StaffNotification offer31 = notification(1103L, StaffNotificationTemplate.OFFER_NEW, 31L, null);
    private final StaffNotification offer12 = notification(877L, StaffNotificationTemplate.OFFER_NEW, 12L,
            LocalDateTime.of(2026, 9, 14, 15, 22, 40));

    {
        when(offers.findAllById(any())).thenReturn(List.of(
                offer(31L, StaffOfferStatus.ACTIVE, LocalDateTime.of(2026, 10, 5, 6, 0)),
                offer(12L, StaffOfferStatus.EXPIRED, LocalDateTime.of(2026, 9, 21, 6, 0))));
    }

    @Test
    @DisplayName("newest first, each offer notification saying whether its offer can still be taken up")
    void inbox() {
        when(notifications.findByStaffMemberIdOrderByIdDesc(eq(CHIPO), any())).thenReturn(
                new PageImpl<>(List.of(launch, offer31, offer12), PageRequest.of(0, 20), 3));

        List<StaffInboxNotification> items = service.inbox(CHIPO, PageRequest.of(0, 20)).getContent();

        assertThat(items).extracting(StaffInboxNotification::id).containsExactly(1388L, 1103L, 877L);
        assertThat(items).extracting(StaffInboxNotification::offerOpen).containsExactly(null, true, false);
        assertThat(items.get(1).kind()).isEqualTo(StaffNotificationTemplate.OFFER_NEW);
        assertThat(items.get(1).readAt()).isNull();
        assertThat(items.get(2).readAt()).isEqualTo(LocalDateTime.of(2026, 9, 14, 15, 22, 40));
    }

    @Test
    @DisplayName("an offer still ACTIVE but past its expiry, or gone, is not open")
    void offerNoLongerOpen() {
        when(offers.findAllById(any())).thenReturn(List.of(
                offer(31L, StaffOfferStatus.ACTIVE, NOW.minusSeconds(1))));
        StaffNotification gone = notification(1500L, StaffNotificationTemplate.OFFER_REFRESHED, 99L, null);
        when(notifications.findByStaffMemberIdOrderByIdDesc(eq(CHIPO), any())).thenReturn(
                new PageImpl<>(List.of(gone, offer31), PageRequest.of(0, 20), 2));

        assertThat(service.inbox(CHIPO, PageRequest.of(0, 20)).getContent())
                .extracting(StaffInboxNotification::offerOpen).containsExactly(false, false);
    }

    @Test
    @DisplayName("marking one read returns it; another member's, or none, is not found; the unread count and read-all")
    void reading() {
        StaffNotification read = notification(1103L, StaffNotificationTemplate.OFFER_NEW, 31L, NOW);
        when(notifications.findByIdAndStaffMemberId(1103L, CHIPO)).thenReturn(Optional.of(read));
        when(offers.findById(31L)).thenReturn(Optional.of(
                offer(31L, StaffOfferStatus.ACTIVE, LocalDateTime.of(2026, 10, 5, 6, 0))));

        StaffInboxNotification marked = service.read(CHIPO, 1103L);

        assertThat(marked.readAt()).isEqualTo(NOW);
        assertThat(marked.offerOpen()).isTrue();
        verify(notifications).markRead(1103L, CHIPO, NOW);

        when(notifications.findByIdAndStaffMemberId(anyLong(), eq(CHIPO))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.read(CHIPO, 999L)).isInstanceOf(NotFoundException.class)
                .hasMessage("Notification 999 not found");

        when(notifications.countByStaffMemberIdAndReadAtIsNull(CHIPO)).thenReturn(2L);
        assertThat(service.unread(CHIPO)).isEqualTo(new StaffInboxUnread(2));
        when(notifications.markAllRead(CHIPO, NOW)).thenReturn(2);
        assertThat(service.readAll(CHIPO)).isEqualTo(new StaffInboxReadAll(2));
    }

    private static StaffNotification notification(Long id, StaffNotificationTemplate template, Long offerId,
                                                  LocalDateTime readAt) {
        return StaffNotification.builder().id(id).staffMemberId(CHIPO).template(template).templateVersion(1)
                .offerId(offerId).title(template.title()).message("The message").createdAt(NOW.minusDays(3))
                .outboundStatus(StaffNotificationOutboundStatus.SENT).readAt(readAt).build();
    }

    private static StaffOffer offer(Long id, StaffOfferStatus status, LocalDateTime expiresAt) {
        return StaffOffer.builder().id(id).status(status).expiresAt(expiresAt).build();
    }
}
