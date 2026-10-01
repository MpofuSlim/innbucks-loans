package zw.co.innbucks.loans.core.staff.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Sends the launch broadcast, to exactly as many members as the preview showed. */
@Data
public class LaunchStaffBroadcastRequest {

    @NotNull(message = "expectedRecipients is required: the recipients count from the launch preview")
    @Min(value = 1, message = "expectedRecipients must be at least 1")
    @Schema(description = "The recipients count from GET /staff-notification-broadcasts/launch-preview. Refused when"
            + " the register has changed since, so nobody sends to a different set of people than they checked",
            example = "1240", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer expectedRecipients;
}
