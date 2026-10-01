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

/**
 * Each member's Staff Grocery Loan standing from loans' own records (FR-SGL-013, FR-SGL-014, FR-SGL-017): ACTIVE_LOAN
 * while one awaits disbursement or is disbursed, ARREARS once a disbursed one is past its due date by more than the
 * grace, or one was written off. Other InnBucks facilities, and the balance itself, are the core banking system's to
 * report; until it does, this is the whole of it.
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
    public Map<Long, Standing> of(Collection<StaffMember> members) {
        if (members.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = members.stream().map(StaffMember::getId).toList();
        LocalDate today = marketTimeZone.today();
        Map<Long, Standing> standings = new HashMap<>();
        for (StaffLoan loan : loanRepository.findByStaffMemberIdInAndStatusIn(ids, BEARING)) {
            Standing standing = loan.inArrearsOn(today, policy.arrearsGraceDays()) ? Standing.ARREARS
                    : Standing.ACTIVE_LOAN;
            // Arrears is reported over an active loan when both apply.
            standings.merge(loan.getStaffMemberId(), standing,
                    (held, found) -> held == Standing.ARREARS ? held : found);
        }
        return standings;
    }
}
