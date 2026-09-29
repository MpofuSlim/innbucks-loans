package zw.co.reikan.loans.core.disbursements;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BookingNotBookedRequest {
    /** The evidence: who at InnBucks confirmed no loan was booked, ideally with their ticket or reference. */
    @NotBlank(message = "A note on how InnBucks confirmed the loan was not booked is required")
    @Size(max = 255, message = "Note must be at most 255 characters")
    private String note;
}
