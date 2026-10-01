package zw.co.innbucks.loans.core.staff.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import zw.co.innbucks.loans.core.staff.StaffMember;

import java.time.LocalDateTime;
import java.util.List;

/** One message to one staff member: its in-app copy, where the message to their phone stands, and every attempt. */
@Schema(description = "One message to one staff member: the in-app copy kept as their inbox, where the message to"
        + " their phone stands, and every attempt to send it")
public record StaffNotificationResponse(
        Long id,
        String employeeNumber,
        String fullName,
        StaffNotificationTemplate template,
        int templateVersion,
        @Schema(description = "The offer it is about, for OFFER_NEW and OFFER_REFRESHED")
        Long offerId,
        Long runId,
        @Schema(description = "The broadcast it belongs to, for LAUNCH")
        Long broadcastId,
        String title,
        String message,
        LocalDateTime createdAt,
        @Schema(description = "PENDING, SENDING, SENT, FAILED (every channel failed) or SKIPPED")
        StaffNotificationOutboundStatus outboundStatus,
        StaffNotificationSkipReason skipReason,
        @Schema(description = "WHATSAPP, or SMS when WhatsApp failed; set once SENT")
        StaffNotificationChannel deliveredChannel,
        LocalDateTime finishedAt,
        List<StaffNotificationDispatchResponse> dispatches) {

    static StaffNotificationResponse of(StaffNotification notification, StaffMember member,
                                        List<StaffNotificationDispatchResponse> dispatches) {
        return new StaffNotificationResponse(notification.getId(),
                member == null ? null : member.getEmployeeNumber(), member == null ? null : member.getFullName(),
                notification.getTemplate(), notification.getTemplateVersion(), notification.getOfferId(),
                notification.getRunId(), notification.getBroadcastId(), notification.getTitle(),
                notification.getMessage(), notification.getCreatedAt(), notification.getOutboundStatus(),
                notification.getSkipReason(), notification.getDeliveredChannel(), notification.getFinishedAt(),
                dispatches);
    }
}
