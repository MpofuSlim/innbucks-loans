package zw.co.innbucks.loans.core.staff.loan;

/** How far past its due date a loan is, in the arrears report's ageing buckets (FR-SGL-045). */
public enum StaffLoanArrearsBucket {
    /** On or before its due date: listed because it was written off, or its borrower is no longer ACTIVE. */
    NOT_DUE,
    DAYS_1_TO_30,
    DAYS_31_TO_60,
    DAYS_61_TO_90,
    OVER_90_DAYS;

    static StaffLoanArrearsBucket of(long daysPastDue) {
        if (daysPastDue <= 0) {
            return NOT_DUE;
        }
        if (daysPastDue <= 30) {
            return DAYS_1_TO_30;
        }
        if (daysPastDue <= 60) {
            return DAYS_31_TO_60;
        }
        return daysPastDue <= 90 ? DAYS_61_TO_90 : OVER_90_DAYS;
    }
}
