package zw.co.reikan.loans.core.disbursements;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class LoanAccountCreationResponse {
    private String reference;
    private boolean success;
    /** Why the application did not succeed; null on success. */
    private String message;
}
