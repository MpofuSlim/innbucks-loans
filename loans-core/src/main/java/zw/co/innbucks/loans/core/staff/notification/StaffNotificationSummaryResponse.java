package zw.co.innbucks.loans.core.staff.notification;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * How a set of staff notifications fared: how many there are, where the message to the phone stands, why any were not
 * sent, and which channel took those that were. Every key is present, with 0 where nothing applies.
 */
@Schema(description = "How a set of staff notifications fared. Every key is present, 0 where nothing applies")
public record StaffNotificationSummaryResponse(
        @Schema(description = "Notifications, each with its in-app copy stored")
        long notifications,
        Map<StaffNotificationOutboundStatus, Long> outboundStatus,
        @Schema(description = "Of those SKIPPED, why")
        Map<StaffNotificationSkipReason, Long> skipped,
        @Schema(description = "Of those SENT, which channel took them")
        Map<StaffNotificationChannel, Long> sentBy) {
}
