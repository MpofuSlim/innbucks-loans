package zw.co.innbucks.loans.core.staff.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.offer.StaffOffer;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Creating staff notifications: one per offer a run issued, worded for a new or a refreshed offer, and the launch
 * broadcast to everyone on the register who has not left, sent once; each with its in-app copy logged at once and sent
 * only after the commit (FR-SGL-019, FR-SGL-020, FR-SGL-023, FR-SGL-024).
 */
class StaffNotificationServiceTest {

    /** Monday 5 October 2026, 08:00:01 in Harare. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 6, 0, 1);

    private final List<StaffMember> members = new ArrayList<>();
    private final List<StaffNotification> notifications = new ArrayList<>();
    private final List<StaffNotificationDispatch> dispatches = new ArrayList<>();
    private StaffNotificationBroadcast launched;
    private StaffMemberRepository memberRepository;
    private StaffNotificationDispatcher dispatcher;
    private AuditService auditService;
    private StaffNotificationService service;

    @BeforeEach
    void setUp() {
        StaffNotificationRepository notificationRepository = mock(StaffNotificationRepository.class);
        when(notificationRepository.saveAll(any())).thenAnswer(i -> {
            List<StaffNotification> saved = new ArrayList<>();
            for (StaffNotification notification : (Iterable<StaffNotification>) i.getArgument(0)) {
                StaffNotification withId = StaffNotification.builder().id((long) notifications.size() + 1)
                        .staffMemberId(notification.getStaffMemberId()).template(notification.getTemplate())
                        .templateVersion(notification.getTemplateVersion()).offerId(notification.getOfferId())
                        .runId(notification.getRunId()).broadcastId(notification.getBroadcastId())
                        .title(notification.getTitle()).message(notification.getMessage())
                        .createdAt(notification.getCreatedAt()).outboundStatus(notification.getOutboundStatus())
                        .build();
                notifications.add(withId);
                saved.add(withId);
            }
            return saved;
        });
        StaffNotificationDispatchRepository dispatchRepository = mock(StaffNotificationDispatchRepository.class);
        when(dispatchRepository.saveAll(any())).thenAnswer(i -> {
            ((Iterable<StaffNotificationDispatch>) i.getArgument(0)).forEach(dispatches::add);
            return i.getArgument(0);
        });
        StaffNotificationBroadcastRepository broadcastRepository = mock(StaffNotificationBroadcastRepository.class);
        when(broadcastRepository.findByKind(StaffNotificationBroadcastKind.LAUNCH))
                .thenAnswer(i -> Optional.ofNullable(launched));
        when(broadcastRepository.saveAndFlush(any())).thenAnswer(i -> {
            StaffNotificationBroadcast broadcast = i.getArgument(0);
            launched = StaffNotificationBroadcast.builder().id(1L).kind(broadcast.getKind())
                    .template(broadcast.getTemplate()).templateVersion(broadcast.getTemplateVersion())
                    .recipients(broadcast.getRecipients()).leftExcluded(broadcast.getLeftExcluded())
                    .createdBy(broadcast.getCreatedBy()).createdAt(broadcast.getCreatedAt()).build();
            return launched;
        });
        StaffNotificationPreferenceRepository preferenceRepository = mock(StaffNotificationPreferenceRepository.class);
        when(preferenceRepository.countOptedOut(any())).thenReturn(1L);
        memberRepository = mock(StaffMemberRepository.class);
        when(memberRepository.findAll()).thenAnswer(i -> members);
        when(memberRepository.findAll(any(Sort.class))).thenAnswer(i -> members);
        dispatcher = mock(StaffNotificationDispatcher.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("credit1");
        auditService = mock(AuditService.class);
        MarketTimeZone market = new MarketTimeZone("ZW", Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        service = new StaffNotificationService(notificationRepository, dispatchRepository, broadcastRepository,
                preferenceRepository, memberRepository, dispatcher, authService, auditService, market) {
            @Override
            public StaffNotificationSummaryResponse summary(Long runId, Long broadcastId,
                                                            StaffNotificationTemplate template, LocalDate from,
                                                            LocalDate to) {
                // The grouped count runs against Postgres; checked live, not here.
                return null;
            }
        };
    }

    private StaffMember member(String employeeNumber, String msisdn, StaffEmploymentStatus status) {
        StaffMember member = StaffMember.builder().id((long) members.size() + 1).employeeNumber(employeeNumber)
                .fullName("Staff " + employeeNumber).msisdn(msisdn).grade("C4").employmentStatus(status).build();
        members.add(member);
        return member;
    }

    private static StaffOffer offer(Long id, StaffMember member, Long replaces) {
        return StaffOffer.builder().id(id).staffMemberId(member.getId()).runId(3L)
                .cycleStart(LocalDate.of(2026, 10, 5)).grade("C4").scoreBand("Band C").gradeLimitChangeId(1L)
                .amount(new BigDecimal("300.00")).issuedAt(NOW).expiresAt(NOW.plusDays(7)).replacesOfferId(replaces)
                .status(StaffOfferStatus.ACTIVE).build();
    }

    @Test
    @DisplayName("each offer gets one notification, OFFER_NEW or OFFER_REFRESHED, naming its amount and lapse on the"
            + " market's clock; the in-app copy is logged at once and sending waits for the commit")
    void notifyOffers() {
        StaffMember nyasha = member("E1001", "263782606983", StaffEmploymentStatus.ACTIVE);
        StaffMember chipo = member("E1012", "0773456789", StaffEmploymentStatus.ACTIVE);

        service.notifyOffers(List.of(offer(11L, nyasha, null), offer(12L, chipo, 4L)),
                members.stream().collect(Collectors.toMap(StaffMember::getId, Function.identity())));

        assertThat(notifications).extracting(StaffNotification::getTemplate, StaffNotification::getOfferId,
                        StaffNotification::getRunId, StaffNotification::getOutboundStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(StaffNotificationTemplate.OFFER_NEW, 11L, 3L,
                                StaffNotificationOutboundStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple(StaffNotificationTemplate.OFFER_REFRESHED, 12L, 3L,
                                StaffNotificationOutboundStatus.PENDING));
        assertThat(notifications.getFirst().getMessage()).isEqualTo("InnBucks Staff Grocery Loan. You have a new offer"
                + " of up to USD 300.00, open until 08.00 on 12 Oct 2026. Log in to the InnBucks app to accept it.");
        assertThat(notifications.getFirst().getTitle()).isEqualTo("Your Staff Grocery Loan offer");
        assertThat(notifications).allSatisfy(notification -> {
            assertThat(notification.getTemplateVersion()).isEqualTo(1);
            assertThat(notification.getCreatedAt()).isEqualTo(NOW);
        });
        assertThat(dispatches).extracting(StaffNotificationDispatch::getNotificationId,
                        StaffNotificationDispatch::getChannel, StaffNotificationDispatch::getStatus,
                        StaffNotificationDispatch::getRecipient)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, StaffNotificationChannel.IN_APP,
                                StaffNotificationDispatchStatus.STORED, "+263782606983"),
                        org.assertj.core.groups.Tuple.tuple(2L, StaffNotificationChannel.IN_APP,
                                StaffNotificationDispatchStatus.STORED, "+263773456789"));
        verify(dispatcher).afterCommit();
    }

    @Test
    @DisplayName("a run that issued nothing creates nothing and wakes nothing")
    void noOffers() {
        service.notifyOffers(List.of(), Map.of());

        assertThat(notifications).isEmpty();
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("the launch goes to everyone who has not left, whatever their offer; once, audited, after the commit")
    void launch() {
        member("E1001", "263782606983", StaffEmploymentStatus.ACTIVE);
        member("E1012", "263773456789", StaffEmploymentStatus.SUSPENDED);
        member("E1043", "263771111222", StaffEmploymentStatus.RESIGNED);
        member("E1044", "263771111333", StaffEmploymentStatus.UNPAID_LEAVE);
        LaunchStaffBroadcastRequest request = new LaunchStaffBroadcastRequest();

        StaffLaunchPreviewResponse preview = service.launchPreview();
        assertThat(preview.recipients()).isEqualTo(3);
        assertThat(preview.leftExcluded()).isEqualTo(1);
        assertThat(preview.optedOut()).isEqualTo(1);
        assertThat(preview.alreadySentAs()).isNull();
        assertThat(preview.message()).isEqualTo(StaffNotificationTemplate.LAUNCH.text());

        request.setExpectedRecipients(preview.recipients());
        StaffNotificationBroadcastResponse broadcast = service.launch(request);

        assertThat(broadcast.recipients()).isEqualTo(3);
        assertThat(broadcast.leftExcluded()).isEqualTo(1);
        assertThat(broadcast.createdBy()).isEqualTo("credit1");
        assertThat(notifications).extracting(StaffNotification::getStaffMemberId).containsExactly(1L, 2L, 4L);
        assertThat(notifications).allSatisfy(notification -> {
            assertThat(notification.getTemplate()).isEqualTo(StaffNotificationTemplate.LAUNCH);
            assertThat(notification.getBroadcastId()).isEqualTo(1L);
            assertThat(notification.getOfferId()).isNull();
            assertThat(notification.getMessage()).isEqualTo(StaffNotificationTemplate.LAUNCH.text());
        });
        assertThat(dispatches).hasSize(3).allSatisfy(dispatch ->
                assertThat(dispatch.getChannel()).isEqualTo(StaffNotificationChannel.IN_APP));
        verify(memberRepository).lockRegister(StaffRegisterService.REGISTER_LOCK);
        verify(dispatcher).afterCommit();
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        assertThat(audit.getValue().build().getEventType()).isEqualTo(StaffNotificationService.LAUNCHED);
        assertThat(audit.getValue().build().getDetail()).isEqualTo("template:LAUNCH;version:1;recipients:3;"
                + "leftExcluded:1");

        assertThatThrownBy(() -> service.launch(request)).isInstanceOf(ConflictException.class)
                .hasMessage("The launch was announced by credit1 (broadcast 1); it is sent once");
        assertThat(service.launchPreview().alreadySentAs()).isEqualTo(1L);
        assertThat(notifications).as("nobody told twice").hasSize(3);
    }

    @Test
    @DisplayName("a register that changed since the preview refuses the launch, and nothing is sent")
    void launchCountChanged() {
        member("E1001", "263782606983", StaffEmploymentStatus.ACTIVE);
        member("E1012", "263773456789", StaffEmploymentStatus.ACTIVE);
        LaunchStaffBroadcastRequest request = new LaunchStaffBroadcastRequest();
        request.setExpectedRecipients(1);

        assertThatThrownBy(() -> service.launch(request)).isInstanceOf(ConflictException.class)
                .hasMessage("The staff register now has 2 members to tell, not the 1 confirmed; check the launch"
                        + " preview again");

        assertThat(launched).isNull();
        assertThat(notifications).isEmpty();
        verify(dispatcher, never()).afterCommit();
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("sending what waits starts a pass and says how many there were")
    void dispatchPending() {
        when(dispatcher.pending()).thenReturn(4L);

        assertThat(service.dispatchPending().pending()).isEqualTo(4);
        verify(dispatcher).requestPass();
    }
}
