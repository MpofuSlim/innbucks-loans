package zw.co.innbucks.loans.core.staff.offer;

import zw.co.innbucks.loans.core.staff.StaffMember;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Which staff members hold an active Staff Grocery Loan, are behind on one, or owe a written-off balance. The weekly
 * run neither offers to nor notifies them (FR-SGL-013, FR-SGL-014, FR-SGL-017), and withdraws any offer they still
 * hold; only a written-off balance can be overridden by Credit.
 */
public interface StaffLoanStanding {

    enum Standing {
        /** Holds a loan under this product that is not yet repaid. */
        ACTIVE_LOAN,
        /** Behind on a loan they still hold: past its due date by more than the grace. Always with ACTIVE_LOAN. */
        ARREARS,
        /** Owes the balance of a loan that was written off. */
        WRITTEN_OFF
    }

    /** @return each member's standings, by staff member id, never empty; a member not in it is clear */
    Map<Long, Set<Standing>> of(Collection<StaffMember> members);
}
