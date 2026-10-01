package zw.co.innbucks.loans.core.staff.loan;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a Staff Grocery Loan stands. Accepted in the SuperApp, it waits for disbursement through the bank's system
 * (BR.NET, FR-SGL-032); disbursement, repayment and write-off are recorded by that integration and the core banking
 * collection, which are not wired yet, so today every loan is AWAITING_DISBURSEMENT or CANCELLED.
 */
public enum StaffLoanStatus {
    /** Accepted; nothing paid yet. Cancellable. */
    AWAITING_DISBURSEMENT,
    /** Paid to GetMore Groceries and the voucher issued; owed until it is collected from salary. */
    DISBURSED,
    /** Collected in full. */
    REPAID,
    /** Stopped before anything was paid, with who and why. */
    CANCELLED,
    /** Written off by Finance with a balance still owed. */
    WRITTEN_OFF;

    /** The statuses a member may hold only one loan in at a time (FR-SGL-013). */
    public static final Set<StaffLoanStatus> OPEN = EnumSet.of(AWAITING_DISBURSEMENT, DISBURSED);

    public boolean open() {
        return OPEN.contains(this);
    }
}
