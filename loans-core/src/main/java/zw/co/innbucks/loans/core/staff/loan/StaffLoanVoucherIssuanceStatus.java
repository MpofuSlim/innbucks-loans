package zw.co.innbucks.loans.core.staff.loan;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where issuing a Staff Grocery Loan's GetMore voucher stands ({@link StaffLoanVoucherIssuance}). Nothing moves it on a
 * timer: an acceptance, a borrower's read, an operator or a cancellation does.
 */
public enum StaffLoanVoucherIssuanceStatus {
    /** Waiting for its next attempt ({@code next_attempt_at}): queued at acceptance, or GetMore could not answer. */
    PENDING,
    /** An attempt holds the claim and may be calling GetMore right now ({@code claimed_at}). */
    CALLING,
    /** The voucher is kept and the loan is DISBURSED. Final. */
    ISSUED,
    /** Stopped for an operator: GetMore refused, or what is on the prefix is not loans' to adopt (its last outcome). */
    PARKED,
    /** The loan was cancelled: every coupon on the prefix, and every code GetMore returned, is being withdrawn. */
    CANCELLING,
    /** The loan was cancelled and nothing of the voucher is left live at GetMore. Final. */
    ABANDONED;

    /** The statuses still to be worked: an attempt, an operator or a cleanup will move them. */
    public static final Set<StaffLoanVoucherIssuanceStatus> OPEN = EnumSet.of(PENDING, CALLING, PARKED, CANCELLING);

    public boolean isFinal() {
        return this == ISSUED || this == ABANDONED;
    }
}
