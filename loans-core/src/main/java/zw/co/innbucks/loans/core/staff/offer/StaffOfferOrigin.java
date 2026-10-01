package zw.co.innbucks.loans.core.staff.offer;

/** How an offer came to be made (FR-SGL-025): by the weekly run, or on demand when the borrower applied. */
public enum StaffOfferOrigin {
    /** Issued by a weekly run, which it belongs to; one per member per cycle. */
    RUN,
    /** Made when the borrower chose "Apply" in the SuperApp without an offer; it belongs to no run. */
    APPLY
}
