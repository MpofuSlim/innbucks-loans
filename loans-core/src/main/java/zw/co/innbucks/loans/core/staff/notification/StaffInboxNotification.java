package zw.co.innbucks.loans.core.staff.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One notification in a staff member's SuperApp inbox (FR-SGL-019, FR-SGL-020): the in-app copy of a message, whether
 * or not it also reached their phone.
 *
 * @param kind      OFFER_NEW, OFFER_REFRESHED or LAUNCH
 * @param offerId   the offer it is about, for an offer notification
 * @param offerOpen for an offer notification: whether that offer can still be taken up, so the app shows "Accept"
 *                  only while it can
 * @param readAt    when they first read it; absent while unread
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A notification in the borrower's inbox")
public record StaffInboxNotification(
        Long id,
        StaffNotificationTemplate kind,
        String title,
        String message,
        LocalDateTime createdAt,
        LocalDateTime readAt,
        Long offerId,
        Boolean offerOpen) {
}
