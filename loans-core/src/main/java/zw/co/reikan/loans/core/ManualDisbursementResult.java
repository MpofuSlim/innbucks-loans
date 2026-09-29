package zw.co.reikan.loans.core;

import lombok.Builder;
import lombok.Data;

/** What a manual recovery payout attempt did — the body of {@code POST /api/loans/{id}/disburse}. */
@Data
@Builder
public class ManualDisbursementResult {

    public enum Outcome {
        /** InnBucks confirmed the payout. */
        DISBURSED,
        /** Nothing was paid — InnBucks refused it, or it never left — so the loan may be tried again. */
        REFUSED,
        /** Nobody knows whether money moved. Every further attempt is blocked until confirmed with InnBucks. */
        IN_DOUBT
    }

    private Outcome outcome;
    /** The one deposit reference every attempt for this loan carries — what to quote to InnBucks. */
    private String reference;
    private String message;
}
