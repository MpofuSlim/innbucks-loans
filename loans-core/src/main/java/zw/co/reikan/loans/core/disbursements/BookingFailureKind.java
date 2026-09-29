package zw.co.reikan.loans.core.disbursements;

/**
 * How the InnBucks pre-approved booking ({@code POST /bank/api/loan/apply/pre-approved})
 * failed. InnBucks books AND pays the loan on that one call, so this is the fact that
 * decides whether a manual recovery payout could pay the loan a second time.
 */
public enum BookingFailureKind {

    /**
     * InnBucks answered and refused (a 4xx, or a non-2xx we read): no booking, so no
     * payout. The only kind a manual recovery payout may follow.
     */
    REFUSED,

    /**
     * The booking may have landed — a 5xx, a timeout, a reset after the request left,
     * an unreadable answer. Held for the inquiry job and an operator; never re-paid.
     */
    AMBIGUOUS
}
