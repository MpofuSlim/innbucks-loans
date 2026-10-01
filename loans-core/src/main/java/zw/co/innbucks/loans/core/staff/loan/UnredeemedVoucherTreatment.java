package zw.co.innbucks.loans.core.staff.loan;

/**
 * What happens to a Staff Grocery Loan whose voucher expires with value unspent (OQ-09, BRD 3.6). A setting until
 * Finance and Legal decide; each loan keeps the treatment it was accepted under, because it is part of the terms the
 * borrower was shown. Applied when the voucher lapses, which needs the disbursement and collection integrations: until
 * they land no voucher is issued, so no loan reaches it.
 */
public enum UnredeemedVoucherTreatment {

    /** The loan is owed in full whatever is spent (the BRD's working position). */
    DEBT_STANDS("If you do not spend the whole voucher before it expires, you still repay the full amount."),
    /** The loan is reduced to what was spent; the unspent value is not owed. */
    REDUCED_TO_AMOUNT_SPENT("If you do not spend the whole voucher before it expires, you repay only what you spent.");

    private final String terms;

    UnredeemedVoucherTreatment(String terms) {
        this.terms = terms;
    }

    /** The sentence the borrower is shown before accepting, and that the agreement can name. */
    public String terms() {
        return terms;
    }
}
