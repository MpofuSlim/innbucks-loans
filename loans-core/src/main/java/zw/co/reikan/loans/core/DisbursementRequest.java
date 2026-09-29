package zw.co.reikan.loans.core;

import lombok.Builder;
import lombok.Data;
import zw.co.reikan.loans.core.loan.DisbursementType;

import java.math.BigDecimal;

@Data
@Builder
public class DisbursementRequest {
    private BigDecimal amount;
    private String mobileNumber;
    private String accountNumber;
    /** The loan reference, quoted in the narration the recipient sees. */
    private String reference;
    /**
     * The deposit's own reference on the wire — stable per loan and reused by every
     * attempt, so InnBucks and an operator can tie all attempts to one payout.
     */
    private String transactionReference;
    private DisbursementType disbursementType;
}
