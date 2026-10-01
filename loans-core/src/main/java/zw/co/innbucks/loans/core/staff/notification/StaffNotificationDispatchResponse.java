package zw.co.innbucks.loans.core.staff.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import zw.co.innbucks.loans.core.staff.StaffMember;

import java.time.LocalDateTime;

/** One attempt to reach a staff member on one channel (FR-SGL-023). */
@Schema(description = "One attempt to reach a staff member on one channel: IN_APP when the notification was stored,"
        + " then SMS, then WhatsApp when the SMS failed")
public record StaffNotificationDispatchResponse(
        Long id,
        Long notificationId,
        String employeeNumber,
        String fullName,
        StaffNotificationChannel channel,
        @Schema(description = "The member's mobile number, E.164", example = "+263772123123")
        String recipient,
        StaffNotificationTemplate template,
        int templateVersion,
        @Schema(description = "STORED (in-app), SENT (accepted by the gateway; handset delivery is not reported back)"
                + " or FAILED")
        StaffNotificationDispatchStatus status,
        @Schema(description = "Our reference for an SMS at the notification API; null otherwise")
        String gatewayReference,
        String failureReason,
        LocalDateTime attemptedAt) {

    static StaffNotificationDispatchResponse of(StaffNotificationDispatch dispatch, StaffMember member) {
        return new StaffNotificationDispatchResponse(dispatch.getId(), dispatch.getNotificationId(),
                member == null ? null : member.getEmployeeNumber(), member == null ? null : member.getFullName(),
                dispatch.getChannel(), dispatch.getRecipient(), dispatch.getTemplate(), dispatch.getTemplateVersion(),
                dispatch.getStatus(), dispatch.getGatewayReference(), dispatch.getFailureReason(),
                dispatch.getAttemptedAt());
    }
}
