package zw.co.innbucks.loans.core.staff.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.offer.StaffOffer;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A staff member's inbox in the SuperApp (FR-SGL-019, FR-SGL-020): every offer and launch notification made for them,
 * newest first, whether or not it also reached their phone. An opted-out member (FR-SGL-022) still finds their offers
 * here. Every call is for the borrower session's own member: a notification of someone else's is not found.
 */
@Service
@RequiredArgsConstructor
public class StaffInboxService {

    private final StaffNotificationRepository notificationRepository;
    private final StaffOfferRepository offerRepository;
    private final MarketTimeZone marketTimeZone;

    /** The member's notifications, newest first, each offer one saying whether its offer can still be taken up. */
    @Transactional(readOnly = true)
    public Page<StaffInboxNotification> inbox(long staffMemberId, Pageable pageable) {
        Page<StaffNotification> page = notificationRepository.findByStaffMemberIdOrderByIdDesc(staffMemberId,
                pageable);
        Map<Long, StaffOffer> offers = offerRepository.findAllById(page.getContent().stream()
                        .map(StaffNotification::getOfferId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(StaffOffer::getId, Function.identity()));
        LocalDateTime now = marketTimeZone.nowUtc();
        return page.map(notification -> item(notification, offers.get(notification.getOfferId()), now));
    }

    @Transactional(readOnly = true)
    public StaffInboxUnread unread(long staffMemberId) {
        return new StaffInboxUnread(notificationRepository.countByStaffMemberIdAndReadAtIsNull(staffMemberId));
    }

    /**
     * Marks one notification read. Reading it again changes nothing: the first read time is kept.
     *
     * @throws NotFoundException no such notification of theirs
     */
    @Transactional
    public StaffInboxNotification read(long staffMemberId, Long notificationId) {
        notificationRepository.markRead(notificationId, staffMemberId, marketTimeZone.nowUtc());
        StaffNotification notification = notificationRepository.findByIdAndStaffMemberId(notificationId,
                staffMemberId).orElseThrow(() -> new NotFoundException("Notification " + notificationId
                + " not found"));
        StaffOffer offer = notification.getOfferId() == null ? null
                : offerRepository.findById(notification.getOfferId()).orElse(null);
        return item(notification, offer, marketTimeZone.nowUtc());
    }

    /** Marks every unread notification of theirs read, in one statement. */
    @Transactional
    public StaffInboxReadAll readAll(long staffMemberId) {
        return new StaffInboxReadAll(notificationRepository.markAllRead(staffMemberId, marketTimeZone.nowUtc()));
    }

    private static StaffInboxNotification item(StaffNotification notification, StaffOffer offer, LocalDateTime now) {
        Boolean offerOpen = notification.getOfferId() == null ? null : offer != null && offer.isOpenAt(now);
        return new StaffInboxNotification(notification.getId(), notification.getTemplate(), notification.getTitle(),
                notification.getMessage(), notification.getCreatedAt(), notification.getReadAt(),
                notification.getOfferId(), offerOpen);
    }
}
