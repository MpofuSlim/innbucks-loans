package zw.co.innbucks.loans.core.staff.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.notifications.NotificationDeliveryException;
import zw.co.innbucks.loans.core.notifications.SmsNotificationClient;
import zw.co.innbucks.loans.core.notifications.WhatsAppNotificationClient;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffOffer;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sending one staff notification: SMS first, WhatsApp when the SMS fails, every attempt logged; and the decisions not to
 * send at all: a notification someone else claimed, an offer that has closed, a member who opted out, and the frequency
 * cap (FR-SGL-019, FR-SGL-021 to FR-SGL-023).
 */
class StaffNotificationSenderTest {

    /** Monday 12 October 2026, 08:00:05 in Harare. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 6, 0, 5);
    private static final String MESSAGE = "InnBucks Staff Grocery Loan. You have a new offer of up to USD 300.00, open"
            + " until 08.00 on 19 Oct 2026. Log in to the InnBucks app to accept it.";

    private final List<StaffOffer> offers = new ArrayList<>();
    private final List<StaffNotificationDispatch> dispatched = new ArrayList<>();
    private StaffNotification notification;
    private StaffNotification saved;
    private StaffNotificationPreference preference;
    private StaffNotification lastSent;
    private boolean claimable = true;
    private StaffNotificationProperties properties;
    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private StaffNotificationSender sender;

    @BeforeEach
    void setUp() {
        StaffNotificationRepository notificationRepository = mock(StaffNotificationRepository.class);
        when(notificationRepository.claim(anyLong(), eq(StaffNotificationOutboundStatus.PENDING),
                eq(StaffNotificationOutboundStatus.SENDING), eq(NOW))).thenAnswer(i -> claimable ? 1 : 0);
        when(notificationRepository.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(saved != null ? saved
                : notification));
        when(notificationRepository.save(any())).thenAnswer(i -> saved = i.getArgument(0));
        when(notificationRepository.findFirstByStaffMemberIdAndOutboundStatusAndTemplateInOrderByIdDesc(anyLong(),
                eq(StaffNotificationOutboundStatus.SENT), any())).thenAnswer(i -> Optional.ofNullable(lastSent));
        StaffNotificationDispatchRepository dispatchRepository = mock(StaffNotificationDispatchRepository.class);
        when(dispatchRepository.saveAll(any())).thenAnswer(i -> {
            Iterable<StaffNotificationDispatch> rows = i.getArgument(0);
            rows.forEach(dispatched::add);
            return rows;
        });
        StaffNotificationPreferenceRepository preferenceRepository = mock(StaffNotificationPreferenceRepository.class);
        when(preferenceRepository.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(preference));
        StaffMemberRepository memberRepository = mock(StaffMemberRepository.class);
        when(memberRepository.findById(7L)).thenReturn(Optional.of(StaffMember.builder().id(7L)
                .employeeNumber("E1001").fullName("Nyasha Dube").msisdn("263782606983").grade("C4")
                .employmentStatus(StaffEmploymentStatus.ACTIVE).build()));
        StaffOfferRepository offerRepository = mock(StaffOfferRepository.class);
        when(offerRepository.findById(anyLong())).thenAnswer(i -> offers.stream()
                .filter(offer -> offer.getId().equals(i.getArgument(0))).findFirst());
        when(offerRepository.findByStaffMemberIdAndIdLessThanOrderByIdDesc(anyLong(), anyLong(), any()))
                .thenAnswer(i -> offers.stream()
                        .filter(offer -> offer.getStaffMemberId().equals(i.getArgument(0))
                                && offer.getId() < (Long) i.getArgument(1))
                        .sorted(Comparator.comparing(StaffOffer::getId).reversed())
                        .limit(((Pageable) i.getArgument(2)).getPageSize())
                        .toList());
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        properties = new StaffNotificationProperties();
        MarketTimeZone market = new MarketTimeZone("ZW", Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        sender = new StaffNotificationSender(notificationRepository, dispatchRepository, preferenceRepository,
                memberRepository, offerRepository, sms, whatsApp, properties, market,
                mock(PlatformTransactionManager.class));
    }

    /** The offer being announced, open for a week from now, and the notification about it, already claimed. */
    private void offerNotification() {
        StaffOffer offer = offer(10L, StaffOfferStatus.ACTIVE, NOW.plusDays(7));
        notification = StaffNotification.builder().id(100L).staffMemberId(7L)
                .template(StaffNotificationTemplate.OFFER_NEW).templateVersion(1).offerId(offer.getId()).runId(3L)
                .title("Your Staff Grocery Loan offer").message(MESSAGE).createdAt(NOW)
                .outboundStatus(StaffNotificationOutboundStatus.SENDING).claimedAt(NOW).version(1L).build();
    }

    private StaffOffer offer(Long id, StaffOfferStatus status, LocalDateTime expiresAt) {
        StaffOffer offer = StaffOffer.builder().id(id).staffMemberId(7L).runId(id).cycleStart(LocalDate.of(2026, 10, 12))
                .grade("C4").scoreBand("Band C").gradeLimitChangeId(1L).amount(new BigDecimal("300.00"))
                .issuedAt(expiresAt.minusDays(7)).expiresAt(expiresAt).status(status).build();
        offers.add(offer);
        return offer;
    }

    /** {@code count} earlier offers, oldest first, each ending as {@code status}. */
    private void earlierOffers(StaffOfferStatus... statuses) {
        long id = 1;
        for (StaffOfferStatus status : statuses) {
            offer(id, status, NOW.minusWeeks(statuses.length - id + 1).plusDays(7));
            id++;
        }
    }

    private void lastMessaged(LocalDateTime at) {
        lastSent = StaffNotification.builder().id(90L).staffMemberId(7L).template(StaffNotificationTemplate.OFFER_NEW)
                .outboundStatus(StaffNotificationOutboundStatus.SENT).deliveredChannel(StaffNotificationChannel.SMS)
                .finishedAt(at).build();
    }

    @Test
    @DisplayName("the SMS goes to the member's number in E.164; the attempt and the outcome are recorded")
    void sendsBySms() {
        offerNotification();

        assertThat(sender.send(100L)).isTrue();

        verify(sms).sendSms(eq("+263782606983"), eq(MESSAGE), anyString());
        verifyNoInteractions(whatsApp);
        assertThat(dispatched).singleElement().satisfies(row -> {
            assertThat(row.getChannel()).isEqualTo(StaffNotificationChannel.SMS);
            assertThat(row.getStatus()).isEqualTo(StaffNotificationDispatchStatus.SENT);
            assertThat(row.getRecipient()).isEqualTo("+263782606983");
            assertThat(row.getTemplate()).isEqualTo(StaffNotificationTemplate.OFFER_NEW);
            assertThat(row.getTemplateVersion()).isEqualTo(1);
            assertThat(row.getGatewayReference()).matches("LOANS-SMS-[0-9a-f-]{36}");
            assertThat(row.getFailureReason()).isNull();
            assertThat(row.getAttemptedAt()).isEqualTo(NOW);
        });
        assertThat(saved.getOutboundStatus()).isEqualTo(StaffNotificationOutboundStatus.SENT);
        assertThat(saved.getDeliveredChannel()).isEqualTo(StaffNotificationChannel.SMS);
        assertThat(saved.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("an SMS that fails falls back to WhatsApp, the ticketing fleet's route; both attempts are logged")
    void fallsBackToWhatsApp() {
        offerNotification();
        doThrow(new NotificationDeliveryException("Notification API rejected SMS: HTTP 400"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        assertThat(sender.send(100L)).isTrue();

        verify(whatsApp).sendCustomNotification("+263782606983", MESSAGE);
        assertThat(dispatched).extracting(StaffNotificationDispatch::getChannel, StaffNotificationDispatch::getStatus,
                        StaffNotificationDispatch::getFailureReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(StaffNotificationChannel.SMS,
                                StaffNotificationDispatchStatus.FAILED, "Notification API rejected SMS: HTTP 400"),
                        org.assertj.core.groups.Tuple.tuple(StaffNotificationChannel.WHATSAPP,
                                StaffNotificationDispatchStatus.SENT, null));
        assertThat(dispatched.get(1).getGatewayReference()).as("the WhatsApp gateway takes no reference").isNull();
        assertThat(saved.getOutboundStatus()).isEqualTo(StaffNotificationOutboundStatus.SENT);
        assertThat(saved.getDeliveredChannel()).isEqualTo(StaffNotificationChannel.WHATSAPP);
    }

    @Test
    @DisplayName("when both channels fail it is FAILED, with each reason; the in-app copy is what the member has")
    void bothFail() {
        offerNotification();
        doThrow(new NotificationDeliveryException("Notification API unreachable: Connection refused"))
                .when(sms).sendSms(anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("WhatsApp is not configured: set WHATSAPP_GATEWAY_URL and"
                + " WHATSAPP_API_KEY")).when(whatsApp).sendCustomNotification(anyString(), anyString());

        assertThat(sender.send(100L)).isTrue();

        assertThat(dispatched).extracting(StaffNotificationDispatch::getStatus)
                .containsExactly(StaffNotificationDispatchStatus.FAILED, StaffNotificationDispatchStatus.FAILED);
        assertThat(dispatched.get(1).getFailureReason()).startsWith("WhatsApp is not configured");
        assertThat(saved.getOutboundStatus()).isEqualTo(StaffNotificationOutboundStatus.FAILED);
        assertThat(saved.getDeliveredChannel()).isNull();
    }

    @Test
    @DisplayName("one already claimed elsewhere is left alone: never sent twice (FR-SGL-024)")
    void claimLost() {
        offerNotification();
        claimable = false;

        assertThat(sender.send(100L)).isFalse();

        verifyNoInteractions(sms, whatsApp);
        assertThat(dispatched).isEmpty();
        assertThat(saved).isNull();
    }

    @Test
    @DisplayName("an offer that closed before its message went out, superseded or past its expiry, is not announced")
    void offerClosed() {
        offerNotification();
        offers.getFirst().setStatus(StaffOfferStatus.WITHDRAWN);

        assertThat(sender.send(100L)).isFalse();

        assertThat(saved.getOutboundStatus()).isEqualTo(StaffNotificationOutboundStatus.SKIPPED);
        assertThat(saved.getSkipReason()).isEqualTo(StaffNotificationSkipReason.OFFER_CLOSED);

        saved = null;
        offers.getFirst().setStatus(StaffOfferStatus.ACTIVE);
        offers.getFirst().setExpiresAt(NOW);
        assertThat(sender.send(100L)).as("ACTIVE but lapsed").isFalse();
        assertThat(saved.getSkipReason()).isEqualTo(StaffNotificationSkipReason.OFFER_CLOSED);
        verifyNoInteractions(sms, whatsApp);
    }

    @Test
    @DisplayName("a member who opted out gets no SMS or WhatsApp, about an offer or the launch (FR-SGL-022)")
    void optedOut() {
        offerNotification();
        preference = StaffNotificationPreference.builder().staffMemberId(7L).offerMessagesOptedOut(true)
                .reason("Asked by phone").updatedBy("hc1").updatedAt(NOW).build();

        assertThat(sender.send(100L)).isFalse();
        assertThat(saved.getSkipReason()).isEqualTo(StaffNotificationSkipReason.OPTED_OUT);

        saved = null;
        notification = StaffNotification.builder().id(101L).staffMemberId(7L)
                .template(StaffNotificationTemplate.LAUNCH).templateVersion(1).broadcastId(1L)
                .title("Introducing the Staff Grocery Loan").message(StaffNotificationTemplate.LAUNCH.text())
                .createdAt(NOW).outboundStatus(StaffNotificationOutboundStatus.SENDING).claimedAt(NOW).build();
        assertThat(sender.send(101L)).isFalse();
        assertThat(saved.getSkipReason()).isEqualTo(StaffNotificationSkipReason.OPTED_OUT);
        verifyNoInteractions(sms, whatsApp);

        saved = null;
        preference.setOfferMessagesOptedOut(false);
        assertThat(sender.send(101L)).as("opted back in").isTrue();
        verify(sms).sendSms(eq("+263782606983"), eq(StaffNotificationTemplate.LAUNCH.text()), anyString());
    }

    @Test
    @DisplayName("frequency cap: after three offers in a row left unanswered, a member messaged within four weeks is"
            + " not messaged again (FR-SGL-021)")
    void capped() {
        earlierOffers(StaffOfferStatus.EXPIRED, StaffOfferStatus.SUPERSEDED, StaffOfferStatus.EXPIRED);
        offerNotification();
        lastMessaged(NOW.minusWeeks(3));

        assertThat(sender.send(100L)).isFalse();

        assertThat(saved.getOutboundStatus()).isEqualTo(StaffNotificationOutboundStatus.SKIPPED);
        assertThat(saved.getSkipReason()).isEqualTo(StaffNotificationSkipReason.FREQUENCY_CAP);
        verifyNoInteractions(sms, whatsApp);
    }

    @Test
    @DisplayName("a capped member is reminded once their last message is four weeks old, counted in market days")
    void cappedReminder() {
        earlierOffers(StaffOfferStatus.EXPIRED, StaffOfferStatus.EXPIRED, StaffOfferStatus.EXPIRED);
        offerNotification();
        // Four weeks ago to the day, a few seconds LATER in the day than now: still four weeks.
        lastMessaged(NOW.minusWeeks(4).plusSeconds(3));

        assertThat(sender.send(100L)).isTrue();
        verify(sms).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the cap does not apply to fewer ignored offers than configured, to a run broken by a withdrawn offer,"
            + " or to a member never messaged")
    void notCapped() {
        earlierOffers(StaffOfferStatus.EXPIRED, StaffOfferStatus.EXPIRED);
        offerNotification();
        lastMessaged(NOW.minusWeeks(1));
        assertThat(sender.send(100L)).as("only two ignored").isTrue();

        offers.clear();
        saved = null;
        earlierOffers(StaffOfferStatus.EXPIRED, StaffOfferStatus.WITHDRAWN, StaffOfferStatus.EXPIRED);
        offerNotification();
        assertThat(sender.send(100L)).as("a withdrawn offer was not ignored").isTrue();

        offers.clear();
        saved = null;
        lastSent = null;
        earlierOffers(StaffOfferStatus.EXPIRED, StaffOfferStatus.EXPIRED, StaffOfferStatus.EXPIRED);
        offerNotification();
        assertThat(sender.send(100L)).as("never messaged").isTrue();
    }

    @Test
    @DisplayName("capped-reminder-weeks 0 holds a capped member back however long ago they were messaged; the cap"
            + " count is configurable")
    void capSettings() {
        properties.setCappedReminderWeeks(0);
        earlierOffers(StaffOfferStatus.EXPIRED, StaffOfferStatus.EXPIRED, StaffOfferStatus.EXPIRED);
        offerNotification();
        lastMessaged(NOW.minusWeeks(52));
        assertThat(sender.send(100L)).isFalse();
        assertThat(saved.getSkipReason()).isEqualTo(StaffNotificationSkipReason.FREQUENCY_CAP);

        saved = null;
        properties.setIgnoredOffersBeforeCap(4);
        assertThat(sender.send(100L)).as("three ignored, the cap now four").isTrue();
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
    }
}
