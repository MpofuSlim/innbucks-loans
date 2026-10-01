package zw.co.innbucks.loans.core.staff.notification;

import io.swagger.v3.oas.annotations.media.Schema;

/** How many staff notifications were waiting when sending was started. */
public record StaffNotificationQueueResponse(
        @Schema(description = "Notifications PENDING when sending was started", example = "4")
        long pending) {
}
