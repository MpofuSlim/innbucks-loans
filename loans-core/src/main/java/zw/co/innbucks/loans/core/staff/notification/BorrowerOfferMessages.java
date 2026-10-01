package zw.co.innbucks.loans.core.staff.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Whether the signed-in borrower receives SMS and WhatsApp messages about offers (FR-SGL-022). Offers are made and
 * shown in the SuperApp inbox either way.
 *
 * @param updatedAt when the choice was last changed, by them or on their behalf; absent when it never has been
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Whether the borrower receives SMS and WhatsApp messages about offers. Offers are made and shown"
        + " in the app either way")
public record BorrowerOfferMessages(boolean optedOut, LocalDateTime updatedAt) {

    static BorrowerOfferMessages of(StaffNotificationPreference preference) {
        return preference == null ? new BorrowerOfferMessages(false, null)
                : new BorrowerOfferMessages(preference.isOfferMessagesOptedOut(), preference.getUpdatedAt());
    }
}
