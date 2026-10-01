package zw.co.innbucks.loans.core.staff.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The borrower's own choice about offer messages, made in the SuperApp (FR-SGL-022). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BorrowerOfferMessagesRequest {

    @NotNull(message = "optedOut is required")
    @Schema(description = "true stops SMS and WhatsApp messages about offers and the launch; false starts them again."
            + " Offers are made and shown in the app either way", example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean optedOut;
}
