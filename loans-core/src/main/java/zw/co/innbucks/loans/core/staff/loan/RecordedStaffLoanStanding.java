package zw.co.innbucks.loans.core.staff.loan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.offer.StaffLoanStanding;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Each member's Staff Grocery Loan standing from loans' own records (FR-SGL-013, FR-SGL-014, FR-SGL-017): ACTIVE_LOAN
 * while one awaits disbursement or is disbursed, ARREARS as well once a disbursed one is past its due date by more than
 * the grace, and WRITTEN_OFF while they owe a written-off one. Other InnBucks facilities, and the balance itself, are
 * the core banking system's to report; until it does, this is the whole of it.
 */
@Component
@RequiredArgsConstructor
public class RecordedStaffLoanStanding implements StaffLoanStanding {

    private static final EnumSet<StaffLoanStatus> BEARING = EnumSet.of(StaffLoanStatus.AWAITING_DISBURSEMENT,
            StaffLoanStatus.DISBURSED, StaffLoanStatus.WRITTEN_OFF);

    private final StaffLoanRepository loanRepository;
    private final StaffLoanPolicy policy;
    private final MarketTimeZone marketTimeZone;

    @Override
    public Map<Long, Set<Standing>> of(Collection<StaffMember> members) {
        if (members.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = members.stream().map(StaffMember::getId).toList();
        LocalDate today = marketTimeZone.today();
        Map<Long, Set<Standing>> standings = new HashMap<>();
        for (StaffLoan loan : loanRepository.findByStaffMemberIdInAndStatusIn(ids, BEARING)) {
            Set<Standing> held = standings.computeIfAbsent(loan.getStaffMemberId(),
                    id -> EnumSet.noneOf(Standing.class));
            if (loan.getStatus() == StaffLoanStatus.WRITTEN_OFF) {
                held.add(Standing.WRITTEN_OFF);
                continue;
            }
            held.add(Standing.ACTIVE_LOAN);
            if (loan.inArrearsOn(today, policy.arrearsGraceDays())) {
                held.add(Standing.ARREARS);
            }
        }
        return standings;
    }
}
