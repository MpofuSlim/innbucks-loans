package zw.co.innbucks.loans.core.staff.offer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.staff.StaffMember;

import java.util.Collection;
import java.util.Map;

/**
 * Staff Grocery Loans are not booked yet (booking and recovery wait on the bank's core, BR.NET), so no member can hold
 * one or be in arrears on one, and every member is clear. When booking lands it brings the real {@link
 * StaffLoanStanding}, and this goes: until then the weekly run excludes nobody for an active loan or arrears.
 */
@Slf4j
@Component
public class NoStaffLoansYet implements StaffLoanStanding {

    public NoStaffLoansYet() {
        log.info("[startup] Staff Grocery Loans are not booked yet: offer runs exclude nobody for an active loan or"
                + " arrears (FR-SGL-017) until booking provides each member's loan standing");
    }

    @Override
    public Map<Long, Standing> of(Collection<StaffMember> members) {
        return Map.of();
    }
}
