package zw.co.innbucks.loans.core.staff.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import zw.co.innbucks.loans.core.staff.StaffMember;

import java.time.LocalDateTime;

/** Whether a member receives offer messages (FR-SGL-022). */
@Schema(description = "Whether a staff member receives SMS and WhatsApp messages about offers. Offers are made and"
        + " shown in-app either way")
public record StaffOfferMessagesResponse(
        String employeeNumber,
        String fullName,
        boolean optedOut,
        @Schema(description = "Why it was last changed; null when it never has been")
        String reason,
        String updatedBy,
        LocalDateTime updatedAt) {

    static StaffOfferMessagesResponse of(StaffMember member, StaffNotificationPreference preference) {
        return preference == null
                ? new StaffOfferMessagesResponse(member.getEmployeeNumber(), member.getFullName(), false, null, null, null)
                : new StaffOfferMessagesResponse(member.getEmployeeNumber(), member.getFullName(),
                preference.isOfferMessagesOptedOut(), preference.getReason(), preference.getUpdatedBy(),
                preference.getUpdatedAt());
    }
}
