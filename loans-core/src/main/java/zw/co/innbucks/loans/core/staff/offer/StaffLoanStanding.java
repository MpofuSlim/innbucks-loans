package zw.co.innbucks.loans.core.staff.offer;

import zw.co.innbucks.loans.core.staff.StaffMember;

import java.util.Collection;
import java.util.Map;

/**
 * Which staff members hold an active Staff Grocery Loan or are in arrears on one. The weekly run neither offers to nor
 * notifies them (FR-SGL-017), and withdraws any offer they still hold.
 */
public interface StaffLoanStanding {

    enum Standing {
        /** Holds a loan under this product that is not yet repaid. */
        ACTIVE_LOAN,
        /** Behind on a loan under this product: reported over ACTIVE_LOAN when both apply. */
        ARREARS
    }

    /** @return the standing of each member that is not clear, by staff member id; a member not in it is clear */
    Map<Long, Standing> of(Collection<StaffMember> members);
}
