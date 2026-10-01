package zw.co.innbucks.loans.core.staff.notification;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** A message to the whole staff register (FR-SGL-020), and how it has fared so far. */
public record StaffNotificationBroadcastResponse(
        Long id,
        StaffNotificationBroadcastKind kind,
        StaffNotificationTemplate template,
        int templateVersion,
        String title,
        String message,
        @Schema(description = "Members of the register it was for: everyone who has not left")
        int recipients,
        @Schema(description = "Members of the register left out because they have left (RESIGNED or TERMINATED)")
        int leftExcluded,
        String createdBy,
        LocalDateTime createdAt,
        StaffNotificationSummaryResponse summary) {

    static StaffNotificationBroadcastResponse of(StaffNotificationBroadcast broadcast,
                                                 StaffNotificationSummaryResponse summary) {
        return new StaffNotificationBroadcastResponse(broadcast.getId(), broadcast.getKind(), broadcast.getTemplate(),
                broadcast.getTemplateVersion(), broadcast.getTemplate().title(), broadcast.getTemplate().text(),
                broadcast.getRecipients(), broadcast.getLeftExcluded(), broadcast.getCreatedBy(),
                broadcast.getCreatedAt(), summary);
    }
}
