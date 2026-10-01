package zw.co.innbucks.loans.core.staff.notification;

import io.swagger.v3.oas.annotations.media.Schema;

/** What the launch broadcast would send, and to how many, if it were sent now. */
@Schema(description = "What the launch broadcast would send, and to how many, if it were sent now")
public record StaffLaunchPreviewResponse(
        StaffNotificationTemplate template,
        int templateVersion,
        String title,
        String message,
        @Schema(description = "Members of the register it would go to: everyone who has not left. Send this number back"
                + " as expectedRecipients to send it", example = "1240")
        int recipients,
        @Schema(description = "Of those, members opted out of offer messages: they get the in-app copy only",
                example = "3")
        long optedOut,
        @Schema(description = "Members of the register left out because they have left", example = "17")
        int leftExcluded,
        @Schema(description = "The broadcast, when the launch has already been announced; it is sent once")
        Long alreadySentAs) {
}
