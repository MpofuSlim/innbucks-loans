package zw.co.innbucks.loans.core.staff.loan;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a cash Staff Grocery Loan's payout stands; null for a voucher loan. The bank books AND pays on one call, so a
 * payout is claimed before it is sent and is never sent twice. An answer that does not prove it was booked, or that it
 * was not, holds it IN_DOUBT, and only the bank's inquiry or a person settles it. No bank answer cancels a loan by
 * itself: only a person ends a payout that has not been paid.
 */
public enum StaffLoanPayoutStatus {
    /** Accepted; waiting to be sent. Cancellable. */
    READY,
    /** Claimed and being sent: the bank may be booking it right now. */
    SENDING,
    /** The bank took it (a 2xx); its inquiry says when the money has landed. */
    BOOKED,
    /** It may or may not have been booked (a timeout, a 5xx, a redirect): asked about, never sent again. */
    IN_DOUBT,
    /** The bank answered that it did not take it (a 4xx): nothing was booked. A person sends it again or cancels. */
    REFUSED,
    /** The money is in the borrower's account; the loan is DISBURSED. */
    PAID,
    /** A person confirmed with the bank that it was never booked; the loan is CANCELLED. */
    FAILED,
    /** Cancelled before anything was booked; the loan is CANCELLED. */
    WITHDRAWN;

    /** Still to be paid or ended: the loan is AWAITING_DISBURSEMENT. */
    public static final Set<StaffLoanPayoutStatus> PENDING = EnumSet.of(READY, SENDING, BOOKED, IN_DOUBT, REFUSED);

    /** The money may already be on its way: the loan must be neither cancelled nor sent again. */
    public boolean inFlight() {
        return this == SENDING || this == BOOKED || this == IN_DOUBT;
    }

    /** Settled by the bank's inquiry. */
    public boolean awaitsInquiry() {
        return this == BOOKED || this == IN_DOUBT;
    }

    /** Nothing was booked, so the loan may still be cancelled. */
    public boolean cancellable() {
        return this == READY || this == REFUSED;
    }
}
