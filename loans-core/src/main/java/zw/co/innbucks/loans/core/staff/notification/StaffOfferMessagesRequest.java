package zw.co.innbucks.loans.core.staff.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A member's choice about offer messages, recorded on their behalf (FR-SGL-022). */
@Data
public class StaffOfferMessagesRequest {

    @NotNull(message = "optedOut is required")
    @Schema(description = "true stops SMS and WhatsApp messages about offers and the launch; false starts them again."
            + " Offers are made and shown in-app either way", example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean optedOut;

    @NotBlank(message = "A reason is required")
    @Size(max = 255, message = "reason must be at most 255 characters")
    @Schema(description = "How the member asked", example = "Asked by phone to the Human Capital help desk, 1 Oct 2026",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;
}
