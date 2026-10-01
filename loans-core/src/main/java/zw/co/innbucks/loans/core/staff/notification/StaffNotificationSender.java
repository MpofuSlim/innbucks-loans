package zw.co.innbucks.loans.core.staff.notification;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.notifications.SmsNotificationClient;
import zw.co.innbucks.loans.core.notifications.WhatsAppNotificationClient;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffOffer;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferStatus;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sends one staff notification to the member's phone: WhatsApp first, and SMS through the InnBucks notification API when
 * WhatsApp fails (FR-SGL-019), the order every Staff Grocery Loan message goes in. Every attempt is logged, sent or not
 * (FR-SGL-023).
 *
 * <p>The notification is claimed first (PENDING to SENDING, in its own short transaction), so it is never sent twice,
 * and whether to send at all is decided then, against the state of things at that moment: an offer that has closed
 * since is not announced, a member who opted out is not messaged (FR-SGL-022), and nor is one the frequency cap holds
 * back (FR-SGL-021). The gateways are called outside any transaction, so a slow one holds no database connection, and
 * the outcome is recorded in a second transaction.
 */
@Slf4j
@Component
public class StaffNotificationSender {

    private static final int FAILURE_LENGTH = 255;
    /** An offer that ended one of these ways was left unanswered. */
    private static final Set<StaffOfferStatus> IGNORED = EnumSet.of(StaffOfferStatus.EXPIRED,
            StaffOfferStatus.SUPERSEDED);
    private static final List<StaffNotificationTemplate> OFFER_TEMPLATES = Arrays.stream(
            StaffNotificationTemplate.values()).filter(StaffNotificationTemplate::aboutAnOffer).toList();

    private final StaffNotificationRepository notificationRepository;
    private final StaffNotificationDispatchRepository dispatchRepository;
    private final StaffNotificationPreferenceRepository preferenceRepository;
    private final StaffMemberRepository memberRepository;
    private final StaffOfferRepository offerRepository;
    private final SmsNotificationClient smsClient;
    private final WhatsAppNotificationClient whatsAppClient;
    private final StaffNotificationProperties properties;
    private final MarketTimeZone marketTimeZone;
    private final TransactionTemplate transactionTemplate;

    public StaffNotificationSender(StaffNotificationRepository notificationRepository,
                                   StaffNotificationDispatchRepository dispatchRepository,
                                   StaffNotificationPreferenceRepository preferenceRepository,
                                   StaffMemberRepository memberRepository,
                                   StaffOfferRepository offerRepository,
                                   SmsNotificationClient smsClient,
                                   WhatsAppNotificationClient whatsAppClient,
                                   StaffNotificationProperties properties,
                                   MarketTimeZone marketTimeZone,
                                   PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.dispatchRepository = dispatchRepository;
        this.preferenceRepository = preferenceRepository;
        this.memberRepository = memberRepository;
        this.offerRepository = offerRepository;
        this.smsClient = smsClient;
        this.whatsAppClient = whatsAppClient;
        this.properties = properties;
        this.marketTimeZone = marketTimeZone;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** What a claimed notification is sent with. */
    private record Claim(Long id, Long staffMemberId, String recipient, String message,
                         StaffNotificationTemplate template, int templateVersion) {
        @Override
        public String toString() {
            return "Claim[id=" + id + ", template=" + template + "]";
        }
    }

    /**
     * Sends the notification if it is still PENDING and should go out.
     *
     * @return whether a gateway was called: what the dispatcher paces
     */
    public boolean send(Long notificationId) {
        Claim claim = transactionTemplate.execute(status -> claim(notificationId));
        if (claim == null) {
            return false;
        }
        List<StaffNotificationDispatch> attempts = new ArrayList<>();
        StaffNotificationChannel delivered = null;
        try {
            whatsAppClient.sendCustomNotification(claim.recipient(), claim.message());
            attempts.add(attempt(claim, StaffNotificationChannel.WHATSAPP, null, null));
            delivered = StaffNotificationChannel.WHATSAPP;
        } catch (RuntimeException whatsAppFailure) {
            attempts.add(attempt(claim, StaffNotificationChannel.WHATSAPP, null, whatsAppFailure));
            log.warn("Staff notification {} WhatsApp to {} failed, falling back to SMS: {}", claim.id(),
                    MsisdnUtils.mask(claim.recipient()), whatsAppFailure.getMessage());
            String reference = "LOANS-SMS-" + UUID.randomUUID();
            try {
                smsClient.sendSms(claim.recipient(), claim.message(), reference);
                attempts.add(attempt(claim, StaffNotificationChannel.SMS, reference, null));
                delivered = StaffNotificationChannel.SMS;
            } catch (RuntimeException smsFailure) {
                attempts.add(attempt(claim, StaffNotificationChannel.SMS, reference, smsFailure));
                log.error("Staff notification {} to {} failed on WhatsApp and SMS; only its in-app copy reached the"
                                + " member: {}", claim.id(), MsisdnUtils.mask(claim.recipient()),
                        smsFailure.getMessage());
            }
        }
        StaffNotificationChannel channel = delivered;
        try {
            transactionTemplate.executeWithoutResult(status -> record(claim.id(), attempts, channel));
        } catch (RuntimeException ex) {
            // It stays SENDING, so it is never sent again; the gateways' own logs carry the reference.
            log.error("Staff notification {} was {} but its outcome could not be recorded", claim.id(),
                    channel == null ? "not delivered" : "sent by " + channel, ex);
        }
        if (channel != null) {
            log.info("Staff notification {} ({}) sent to {} by {}", claim.id(), claim.template(),
                    MsisdnUtils.mask(claim.recipient()), channel);
        }
        return true;
    }

    /** Claims the notification and decides whether it goes out; null when it is not this caller's to send. */
    private Claim claim(Long id) {
        LocalDateTime now = marketTimeZone.nowUtc();
        if (notificationRepository.claim(id, StaffNotificationOutboundStatus.PENDING,
                StaffNotificationOutboundStatus.SENDING, now) == 0) {
            return null;
        }
        StaffNotification notification = notificationRepository.findById(id).orElseThrow();
        StaffNotificationSkipReason skip = skipReason(notification, now);
        if (skip != null) {
            notification.skip(skip, now);
            notificationRepository.save(notification);
            log.info("Staff notification {} ({}) not sent to the member's phone: {}", id, notification.getTemplate(),
                    skip);
            return null;
        }
        StaffMember member = memberRepository.findById(notification.getStaffMemberId()).orElseThrow();
        return new Claim(id, member.getId(), MsisdnUtils.toE164(member.getMsisdn()), notification.getMessage(),
                notification.getTemplate(), notification.getTemplateVersion());
    }

    private StaffNotificationSkipReason skipReason(StaffNotification notification, LocalDateTime now) {
        if (notification.getTemplate().aboutAnOffer()) {
            StaffOffer offer = offerRepository.findById(notification.getOfferId()).orElseThrow();
            if (offer.getStatus() != StaffOfferStatus.ACTIVE || !offer.getExpiresAt().isAfter(now)) {
                return StaffNotificationSkipReason.OFFER_CLOSED;
            }
        }
        boolean optedOut = preferenceRepository.findById(notification.getStaffMemberId())
                .map(StaffNotificationPreference::isOfferMessagesOptedOut)
                .orElse(false);
        if (optedOut) {
            return StaffNotificationSkipReason.OPTED_OUT;
        }
        if (notification.getTemplate().aboutAnOffer() && capped(notification, now)) {
            return StaffNotificationSkipReason.FREQUENCY_CAP;
        }
        return null;
    }

    /**
     * Whether the frequency cap holds this offer message back (FR-SGL-021): the member let their last
     * {@code ignored-offers-before-cap} offers lapse or be replaced unanswered, and was sent an offer message within the
     * last {@code capped-reminder-weeks} weeks. Counted in market days, so a weekly run lands on the reminder week
     * exactly rather than a few seconds short of it.
     */
    private boolean capped(StaffNotification notification, LocalDateTime now) {
        int cap = properties.getIgnoredOffersBeforeCap();
        List<StaffOffer> earlier = offerRepository.findByStaffMemberIdAndIdLessThanOrderByIdDesc(
                notification.getStaffMemberId(), notification.getOfferId(), PageRequest.of(0, cap));
        boolean ignoredInARow = earlier.size() == cap && earlier.stream()
                .allMatch(offer -> IGNORED.contains(offer.getStatus())
                        || (offer.getStatus() == StaffOfferStatus.ACTIVE && !offer.getExpiresAt().isAfter(now)));
        if (!ignoredInARow) {
            return false;
        }
        int weeks = properties.getCappedReminderWeeks();
        if (weeks == 0) {
            return true;
        }
        return notificationRepository.findFirstByStaffMemberIdAndOutboundStatusAndTemplateInOrderByIdDesc(
                        notification.getStaffMemberId(), StaffNotificationOutboundStatus.SENT, OFFER_TEMPLATES)
                .map(last -> ChronoUnit.DAYS.between(marketTimeZone.localDay(last.getFinishedAt()),
                        marketTimeZone.localDay(now)) < weeks * 7L)
                .orElse(false);
    }

    private StaffNotificationDispatch attempt(Claim claim, StaffNotificationChannel channel, String reference,
                                              RuntimeException failure) {
        return StaffNotificationDispatch.builder()
                .notificationId(claim.id())
                .staffMemberId(claim.staffMemberId())
                .channel(channel)
                .recipient(claim.recipient())
                .template(claim.template())
                .templateVersion(claim.templateVersion())
                .status(failure == null ? StaffNotificationDispatchStatus.SENT : StaffNotificationDispatchStatus.FAILED)
                .gatewayReference(reference)
                .failureReason(failure == null ? null : StringUtils.abbreviate(StringUtils.defaultIfBlank(
                        failure.getMessage(), failure.getClass().getSimpleName()), FAILURE_LENGTH))
                .attemptedAt(marketTimeZone.nowUtc())
                .build();
    }

    private void record(Long id, List<StaffNotificationDispatch> attempts, StaffNotificationChannel delivered) {
        dispatchRepository.saveAll(attempts);
        StaffNotification notification = notificationRepository.findById(id).orElseThrow();
        notification.finish(delivered, marketTimeZone.nowUtc());
        notificationRepository.save(notification);
    }
}
